package ape.crank.ssh

import ape.crank.model.ConnectionConfig
import ape.crank.model.KnownHostsPolicy
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.session.SessionHeartbeatController
import java.io.OutputStream
import java.net.SocketAddress
import java.nio.file.Paths
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages a single SSH session with automatic reconnection on failure.
 */
class SshSessionWorker(
    private val client: SshClient,
    private val config: ConnectionConfig,
    val sessionId: String
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    // ------------------------------------------------------------------ callbacks

    var onData: ((ByteArray) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    // ------------------------------------------------------------------ observable state

    val currentState: State get() = state.get()
    var lastErrorMessage: String? = null
        private set
    @Volatile var nextReconnectTimeMs: Long = 0L
        private set
    val reconnectAttemptNumber: Int get() = reconnectAttempt.get()
    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    // Data rate tracking
    private val recentChunks = mutableListOf<Pair<Long, Int>>()

    fun getRecentDataRate(): Double {
        val now = System.currentTimeMillis()
        synchronized(recentChunks) {
            recentChunks.removeAll { now - it.first > 10_000 }
            if (recentChunks.isEmpty()) return 0.0
            val total = recentChunks.sumOf { it.second.toLong() }
            val elapsed = (now - recentChunks.first().first).coerceAtLeast(1L)
            return total * 1000.0 / elapsed
        }
    }

    // ------------------------------------------------------------------ internal state

    private val state = AtomicReference(State.DISCONNECTED)
    private var session: ClientSession? = null
    private var channel: ChannelShell? = null
    private var channelOutput: OutputStream? = null
    private var readerThread: Thread? = null
    private val reconnectAttempt = AtomicInteger(0)
    private val reconnectEnabled = AtomicBoolean(false)
    private val shutdownRequested = AtomicBoolean(false)

    // ------------------------------------------------------------------ public API

    /**
     * Establishes the SSH connection synchronously. Throws on failure.
     */
    fun connect() {
        if (shutdownRequested.get()) return
        lastErrorMessage = null
        transition(State.CONNECTING)

        try {
            val keyProvider = FileKeyPairProvider(Paths.get(config.privateKeyPath))
            val keyPairs = keyProvider.loadKeys(null).toList()
            if (keyPairs.isEmpty()) {
                throw IllegalStateException("No key pairs loaded from ${config.privateKeyPath}")
            }

            val timeoutSec = config.connectionTimeoutSeconds.toLong()

            // Synchronize verifier-set + connect to prevent races between sessions
            // with different knownHostsPolicy values sharing the same SshClient.
            val newSession = synchronized(connectLock) {
                client.serverKeyVerifier = buildKeyVerifier()
                client.connect(config.username, config.host, config.port)
                    .verify(timeoutSec, TimeUnit.SECONDS)
                    .session
            }

            for (kp in keyPairs) {
                newSession.addPublicKeyIdentity(kp)
            }
            newSession.auth().verify(timeoutSec, TimeUnit.SECONDS)

            if (config.keepAliveIntervalSeconds > 0) {
                newSession.setSessionHeartbeat(
                    SessionHeartbeatController.HeartbeatType.IGNORE,
                    java.time.Duration.ofSeconds(config.keepAliveIntervalSeconds.toLong())
                )
            }

            val shell = newSession.createShellChannel()
            shell.setPtyType("xterm-256color")
            shell.setPtyColumns(80)
            shell.setPtyLines(24)

            for ((key, value) in config.environmentVariables) {
                shell.setEnv(key, value)
            }

            shell.open().verify(timeoutSec, TimeUnit.SECONDS)

            session = newSession
            channel = shell
            channelOutput = shell.invertedIn

            reconnectAttempt.set(0)
            nextReconnectTimeMs = 0

            transition(State.CONNECTED)
            startReaderThread(shell)
        } catch (e: Exception) {
            lastErrorMessage = e.message ?: e.javaClass.simpleName
            System.err.println("[SshSessionWorker:$sessionId] connect failed: ${e.message}")
            transition(State.DISCONNECTED)
            throw e
        }
    }

    /**
     * Connects asynchronously on a background thread.
     * On failure, automatically starts reconnection with backoff.
     */
    fun connectAsync() {
        if (shutdownRequested.get()) return
        val thread = Thread({
            try {
                connect()
            } catch (e: Exception) {
                if (!shutdownRequested.get()) {
                    reconnectWithBackoff()
                }
            }
        }, "ssh-connect-$sessionId")
        thread.isDaemon = true
        thread.start()
    }

    fun sendData(data: String) {
        try {
            val out = channelOutput ?: return
            val bytes = data.toByteArray(Charsets.UTF_8)
            out.write(bytes)
            out.flush()
            bytesSent.addAndGet(bytes.size.toLong())
        } catch (e: Exception) {
            System.err.println("[SshSessionWorker:$sessionId] sendData failed: ${e.message}")
            handleConnectionLost()
        }
    }

    fun resize(cols: Int, rows: Int) {
        try {
            channel?.sendWindowChange(cols, rows)
        } catch (e: Exception) {
            System.err.println("[SshSessionWorker:$sessionId] resize failed: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            readerThread?.interrupt()
            readerThread = null
            channel?.close(false)
            channel = null
            channelOutput = null
            session?.close(false)
            session = null
        } catch (e: Exception) {
            System.err.println("[SshSessionWorker:$sessionId] disconnect error: ${e.message}")
        } finally {
            transition(State.DISCONNECTED)
        }
    }

    fun isConnected(): Boolean {
        val ch = channel ?: return false
        return ch.isOpen && !ch.isClosed && !ch.isClosing
    }

    /**
     * Exponential-backoff reconnection: base 1s, max 60s, +/-25% jitter.
     */
    fun reconnectWithBackoff() {
        if (shutdownRequested.get()) return
        reconnectEnabled.set(true)
        transition(State.RECONNECTING)

        val thread = Thread({
            while (reconnectEnabled.get() && !shutdownRequested.get()) {
                val attempt = reconnectAttempt.getAndIncrement()
                val baseDelay = minOf(1000L * (1L shl minOf(attempt, 16)), 60_000L)
                val jitter = (baseDelay * 0.25 * (Math.random() * 2 - 1)).toLong()
                val delay = maxOf(baseDelay + jitter, 500L)

                nextReconnectTimeMs = System.currentTimeMillis() + delay

                System.err.println(
                    "[SshSessionWorker:$sessionId] reconnect attempt ${attempt + 1} in ${delay}ms"
                )

                try { Thread.sleep(delay) } catch (_: InterruptedException) { break }

                nextReconnectTimeMs = 0
                if (shutdownRequested.get() || !reconnectEnabled.get()) break

                try {
                    disconnect()
                    connect()
                    reconnectEnabled.set(false)
                    return@Thread
                } catch (e: Exception) {
                    System.err.println(
                        "[SshSessionWorker:$sessionId] reconnect attempt ${attempt + 1} failed: ${e.message}"
                    )
                    transition(State.RECONNECTING)
                }
            }
        }, "ssh-reconnect-$sessionId")
        thread.isDaemon = true
        thread.start()
    }

    fun shutdown() {
        shutdownRequested.set(true)
        reconnectEnabled.set(false)
        nextReconnectTimeMs = 0
        disconnect()
    }

    // ------------------------------------------------------------------ internals

    companion object {
        /**
         * Lock to serialize the key-verifier + connect window. MINA SSHD only
         * supports a client-level ServerKeyVerifier, so we must prevent two
         * sessions from racing through configureKeyVerifier -> connect.
         */
        private val connectLock = Any()
    }

    private fun transition(newState: State) {
        val prev = state.getAndSet(newState)
        if (prev != newState) {
            try { onStateChanged?.invoke(newState) } catch (_: Exception) {}
        }
    }

    private fun buildKeyVerifier(): ServerKeyVerifier {
        return when (config.knownHostsPolicy) {
            KnownHostsPolicy.TRUST_ALL -> AcceptAllServerKeyVerifier.INSTANCE
            KnownHostsPolicy.ACCEPT_NEW -> {
                val delegate = AcceptAllServerKeyVerifier.INSTANCE
                val knownHostsPath = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts")
                if (knownHostsPath.toFile().exists()) {
                    DefaultKnownHostsServerKeyVerifier(delegate, true, knownHostsPath)
                } else delegate
            }
            KnownHostsPolicy.STRICT -> {
                val strictDelegate = object : ServerKeyVerifier {
                    override fun verifyServerKey(
                        clientSession: ClientSession,
                        remoteAddress: SocketAddress,
                        serverKey: PublicKey
                    ): Boolean = false
                }
                val knownHostsPath = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts")
                DefaultKnownHostsServerKeyVerifier(strictDelegate, false, knownHostsPath)
            }
        }
    }

    private fun startReaderThread(shell: ChannelShell) {
        val thread = Thread({
            val buf = ByteArray(8192)
            try {
                val input = shell.invertedOut
                while (!Thread.currentThread().isInterrupted && isConnected()) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        bytesReceived.addAndGet(n.toLong())
                        synchronized(recentChunks) {
                            recentChunks.add(Pair(System.currentTimeMillis(), n))
                        }
                        val chunk = buf.copyOf(n)
                        try { onData?.invoke(chunk) } catch (_: Exception) {}
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                if (!shutdownRequested.get()) {
                    System.err.println("[SshSessionWorker:$sessionId] reader error: ${e.message}")
                }
            }
            if (!shutdownRequested.get() && !reconnectEnabled.get()) {
                handleConnectionLost()
            }
        }, "ssh-reader-$sessionId")
        thread.isDaemon = true
        thread.start()
        readerThread = thread
    }

    private fun handleConnectionLost() {
        if (shutdownRequested.get()) return
        lastErrorMessage = "Connection lost"
        transition(State.DISCONNECTED)
        reconnectWithBackoff()
    }
}
