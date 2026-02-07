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
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.common.session.SessionHeartbeatController
import java.io.OutputStream
import java.net.SocketAddress
import java.nio.file.Paths
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages a single SSH session with automatic reconnection on failure.
 *
 * Each worker owns one [ClientSession] and one [ChannelShell], reading
 * remote output on a daemon thread and forwarding it to [onData].
 */
class SshSessionWorker(
    private val client: SshClient,
    private val config: ConnectionConfig,
    val sessionId: String
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    /** Called on the reader thread whenever bytes arrive from the remote shell. */
    var onData: ((ByteArray) -> Unit)? = null

    /** Called whenever the connection state changes. */
    var onStateChanged: ((State) -> Unit)? = null

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
     * Establishes the SSH connection, authenticates, opens a shell channel
     * with a PTY, and starts the background reader thread.
     */
    fun connect() {
        if (shutdownRequested.get()) return
        transition(State.CONNECTING)

        try {
            configureKeyVerifier()

            // Load the private key -------------------------------------------------
            val keyProvider = FileKeyPairProvider(Paths.get(config.privateKeyPath))
            val keyPairs = keyProvider.loadKeys(null).toList()
            if (keyPairs.isEmpty()) {
                throw IllegalStateException("No key pairs loaded from ${config.privateKeyPath}")
            }

            // Connect and authenticate ---------------------------------------------
            val timeoutSec = config.connectionTimeoutSeconds.toLong()
            val newSession = client.connect(config.username, config.host, config.port)
                .verify(timeoutSec, TimeUnit.SECONDS)
                .session

            for (kp in keyPairs) {
                newSession.addPublicKeyIdentity(kp)
            }
            newSession.auth().verify(timeoutSec, TimeUnit.SECONDS)

            // Keep-alive -----------------------------------------------------------
            if (config.keepAliveIntervalSeconds > 0) {
                newSession.setSessionHeartbeat(
                    SessionHeartbeatController.HeartbeatType.IGNORE,
                    java.time.Duration.ofSeconds(config.keepAliveIntervalSeconds.toLong())
                )
            }

            // Open shell channel with PTY ------------------------------------------
            val shell = newSession.createShellChannel()
            shell.setPtyType("xterm-256color")
            shell.setPtyColumns(80)
            shell.setPtyLines(24)

            // Environment variables
            for ((key, value) in config.environmentVariables) {
                shell.setEnv(key, value)
            }

            shell.open().verify(timeoutSec, TimeUnit.SECONDS)

            // Store references -----------------------------------------------------
            session = newSession
            channel = shell
            channelOutput = shell.invertedIn   // stream we write to for sending data

            // Reset reconnect counter on success
            reconnectAttempt.set(0)

            transition(State.CONNECTED)
            startReaderThread(shell)
        } catch (e: Exception) {
            System.err.println("[SshSessionWorker:$sessionId] connect failed: ${e.message}")
            transition(State.DISCONNECTED)
            throw e
        }
    }

    /**
     * Sends raw data (typically user keystrokes) to the remote shell.
     */
    fun sendData(data: String) {
        try {
            val out = channelOutput ?: return
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            System.err.println("[SshSessionWorker:$sessionId] sendData failed: ${e.message}")
            handleConnectionLost()
        }
    }

    /**
     * Sends a window-change (resize) request to the remote PTY.
     */
    fun resize(cols: Int, rows: Int) {
        try {
            val ch = channel ?: return
            ch.sendWindowChange(cols, rows)
        } catch (e: Exception) {
            System.err.println("[SshSessionWorker:$sessionId] resize failed: ${e.message}")
        }
    }

    /**
     * Gracefully closes the channel and session.
     */
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

    /**
     * Returns `true` when the shell channel is open and connected.
     */
    fun isConnected(): Boolean {
        val ch = channel ?: return false
        return ch.isOpen && !ch.isClosed && !ch.isClosing
    }

    /**
     * Initiates exponential-backoff reconnection in a background daemon thread.
     *
     * Backoff schedule: base 1 s, doubles each attempt up to 60 s, with +/-25 % jitter.
     */
    fun reconnectWithBackoff() {
        if (shutdownRequested.get()) return
        reconnectEnabled.set(true)
        transition(State.RECONNECTING)

        val thread = Thread({
            while (reconnectEnabled.get() && !shutdownRequested.get()) {
                val attempt = reconnectAttempt.getAndIncrement()
                val baseDelay = minOf(1000L * (1L shl minOf(attempt, 16)), 60_000L)
                val jitter = (baseDelay * 0.25 * (Math.random() * 2 - 1)).toLong() // +/-25 %
                val delay = maxOf(baseDelay + jitter, 500L)

                System.err.println(
                    "[SshSessionWorker:$sessionId] reconnect attempt ${attempt + 1} in ${delay}ms"
                )

                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    break
                }

                if (shutdownRequested.get() || !reconnectEnabled.get()) break

                try {
                    disconnect()          // clean up prior remnants
                    connect()             // throws on failure
                    reconnectEnabled.set(false)
                    return@Thread         // success
                } catch (e: Exception) {
                    System.err.println(
                        "[SshSessionWorker:$sessionId] reconnect attempt ${attempt + 1} failed: ${e.message}"
                    )
                }
            }
        }, "ssh-reconnect-$sessionId")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Permanently stops this worker: cancels any pending reconnection and disconnects.
     */
    fun shutdown() {
        shutdownRequested.set(true)
        reconnectEnabled.set(false)
        disconnect()
    }

    // ------------------------------------------------------------------ internals

    private fun transition(newState: State) {
        val prev = state.getAndSet(newState)
        if (prev != newState) {
            try {
                onStateChanged?.invoke(newState)
            } catch (e: Exception) {
                System.err.println("[SshSessionWorker:$sessionId] onStateChanged callback error: ${e.message}")
            }
        }
    }

    /**
     * Configure the SSH client's server key verifier based on the connection's
     * [KnownHostsPolicy].
     */
    private fun configureKeyVerifier() {
        val verifier: ServerKeyVerifier = when (config.knownHostsPolicy) {
            KnownHostsPolicy.TRUST_ALL -> AcceptAllServerKeyVerifier.INSTANCE

            KnownHostsPolicy.ACCEPT_NEW -> {
                // Accept-new: wrap a default known-hosts verifier so that
                // unknown keys are automatically accepted and saved.
                val delegate = AcceptAllServerKeyVerifier.INSTANCE
                val knownHostsPath = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts")
                if (knownHostsPath.toFile().exists()) {
                    DefaultKnownHostsServerKeyVerifier(delegate, true, knownHostsPath)
                } else {
                    // If there is no known_hosts file yet, just accept everything
                    delegate
                }
            }

            KnownHostsPolicy.STRICT -> {
                // Strict: reject unknown hosts. Known hosts file must already
                // contain the server key.
                val strictDelegate = object : ServerKeyVerifier {
                    override fun verifyServerKey(
                        clientSession: ClientSession,
                        remoteAddress: SocketAddress,
                        serverKey: PublicKey
                    ): Boolean = false   // deny anything the known-hosts file doesn't match
                }
                val knownHostsPath = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts")
                DefaultKnownHostsServerKeyVerifier(strictDelegate, false, knownHostsPath)
            }
        }
        client.serverKeyVerifier = verifier
    }

    /**
     * Starts a daemon thread that continuously reads from the channel's output
     * stream and dispatches data via [onData].
     */
    private fun startReaderThread(shell: ChannelShell) {
        val thread = Thread({
            val buffer = ByteArray(8192)
            try {
                val input = shell.invertedOut  // stream we read from for receiving data
                while (!Thread.currentThread().isInterrupted && isConnected()) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        try {
                            onData?.invoke(chunk)
                        } catch (e: Exception) {
                            System.err.println(
                                "[SshSessionWorker:$sessionId] onData callback error: ${e.message}"
                            )
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Expected on disconnect
            } catch (e: Exception) {
                if (!shutdownRequested.get()) {
                    System.err.println("[SshSessionWorker:$sessionId] reader error: ${e.message}")
                }
            }

            // If the reader exits unexpectedly (not due to explicit shutdown),
            // trigger a reconnect.
            if (!shutdownRequested.get() && reconnectEnabled.get().not()) {
                handleConnectionLost()
            }
        }, "ssh-reader-$sessionId")
        thread.isDaemon = true
        thread.start()
        readerThread = thread
    }

    /**
     * Called when the connection drops unexpectedly. Transitions to DISCONNECTED
     * and initiates reconnection.
     */
    private fun handleConnectionLost() {
        if (shutdownRequested.get()) return
        transition(State.DISCONNECTED)
        reconnectWithBackoff()
    }
}
