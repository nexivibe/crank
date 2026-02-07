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
import java.io.PrintWriter
import java.io.StringWriter
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    /** A single error entry with full context. */
    data class ErrorEntry(
        val timestamp: Long,
        val phase: String,
        val message: String,
        val fullTrace: String
    ) {
        fun format(): String {
            val time = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp))
            return "[$time] $phase: $message"
        }
    }

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

    /** Recent errors, most recent last. Capped at 50 entries. */
    val errorHistory: MutableList<ErrorEntry> = mutableListOf()
        @Synchronized get

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

    @Synchronized
    private fun recordError(phase: String, e: Exception) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val message = buildErrorChain(e)
        lastErrorMessage = message
        errorHistory.add(ErrorEntry(System.currentTimeMillis(), phase, message, sw.toString()))
        if (errorHistory.size > 50) errorHistory.removeAt(0)
    }

    /** Build a readable chain of causes. */
    private fun buildErrorChain(e: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = e
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            val msg = current.message ?: current.javaClass.simpleName
            parts.add(msg)
            current = current.cause
        }
        return parts.joinToString(" -> ")
    }

    @Synchronized
    fun getErrorHistorySnapshot(): List<ErrorEntry> = errorHistory.toList()

    @Synchronized
    fun getFullErrorReport(): String {
        if (errorHistory.isEmpty()) return "No errors recorded."
        val sb = StringBuilder()
        sb.appendLine("=== SSH Error Report for ${config.displayName()} ===")
        sb.appendLine("Session ID: $sessionId")
        sb.appendLine("Host: ${config.username}@${config.host}:${config.port}")
        sb.appendLine("Key: ${config.privateKeyPath}")
        sb.appendLine("Known Hosts Policy: ${config.knownHostsPolicy}")
        sb.appendLine("Current State: ${currentState}")
        sb.appendLine()
        for ((i, entry) in errorHistory.withIndex()) {
            sb.appendLine("--- Error ${i + 1} ---")
            sb.appendLine(entry.format())
            sb.appendLine(entry.fullTrace)
        }
        return sb.toString()
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
        transition(State.CONNECTING)

        try {
            // -- Load private key with diagnostics
            val keyPath = Paths.get(config.privateKeyPath)
            if (!Files.exists(keyPath)) {
                throw IllegalStateException(
                    "Private key file not found: ${config.privateKeyPath}"
                )
            }

            val keyProvider = FileKeyPairProvider(keyPath)
            val keyPairs = try {
                keyProvider.loadKeys(null).toList()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to load private key '${config.privateKeyPath}': ${e.message}" +
                    " (if the key is passphrase-protected, Crank does not yet support encrypted keys)", e
                )
            }
            if (keyPairs.isEmpty()) {
                throw IllegalStateException(
                    "No key pairs loaded from '${config.privateKeyPath}'. " +
                    "Ensure the file contains a valid private key in OpenSSH, PEM, or PKCS8 format."
                )
            }

            System.err.println(
                "[SshSessionWorker:$sessionId] loaded ${keyPairs.size} key(s) " +
                "from ${config.privateKeyPath} (${keyPairs.map { it.private.algorithm }})"
            )

            val timeoutSec = config.connectionTimeoutSeconds.toLong()

            // -- TCP connect + host key verification (serialized across workers)
            val newSession = synchronized(connectLock) {
                client.serverKeyVerifier = buildKeyVerifier()
                client.connect(config.username, config.host, config.port)
                    .verify(timeoutSec, TimeUnit.SECONDS)
                    .session
            }

            // -- Authentication
            for (kp in keyPairs) {
                newSession.addPublicKeyIdentity(kp)
            }
            try {
                newSession.auth().verify(timeoutSec, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Authentication failed for ${config.username}@${config.host}:${config.port}: ${e.message}", e
                )
            }

            if (config.keepAliveIntervalSeconds > 0) {
                newSession.setSessionHeartbeat(
                    SessionHeartbeatController.HeartbeatType.IGNORE,
                    java.time.Duration.ofSeconds(config.keepAliveIntervalSeconds.toLong())
                )
            }

            // -- Open shell channel
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
            lastErrorMessage = null

            transition(State.CONNECTED)
            startReaderThread(shell)
        } catch (e: Exception) {
            recordError("connect", e)
            System.err.println("[SshSessionWorker:$sessionId] connect failed: $lastErrorMessage")
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
                    recordError("reconnect attempt ${attempt + 1}", e)
                    System.err.println(
                        "[SshSessionWorker:$sessionId] reconnect attempt ${attempt + 1} failed: $lastErrorMessage"
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
        recordError("reader", Exception("Connection lost"))
        transition(State.DISCONNECTED)
        reconnectWithBackoff()
    }
}
