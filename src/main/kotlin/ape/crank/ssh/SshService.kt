package ape.crank.ssh

import ape.crank.model.ConnectionConfig
import ape.crank.model.TerminalSession
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.compression.BuiltinCompressions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Application-wide SSH service that owns the single [SshClient] instance
 * and manages all [SshSessionWorker] instances keyed by session ID.
 */
class SshService {

    /** The shared Apache MINA SSHD client, started once and reused for all connections. */
    private val sshClient: SshClient = createClient()

    /** Active workers keyed by terminal session ID. */
    private val workers = ConcurrentHashMap<String, SshSessionWorker>()

    /** Executor used to stagger initial connection attempts at startup. */
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { runnable ->
        Thread(runnable, "ssh-startup-scheduler").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ public API

    /**
     * Creates a new [SshSessionWorker] for the given connection configuration,
     * stores it, and initiates the connection.
     *
     * @return the created worker
     */
    fun startSession(config: ConnectionConfig, sessionId: String): SshSessionWorker {
        // Shut down any existing worker for this session ID
        workers[sessionId]?.shutdown()

        val worker = SshSessionWorker(sshClient, config, sessionId)
        workers[sessionId] = worker

        try {
            worker.connect()
        } catch (e: Exception) {
            System.err.println("[SshService] Failed initial connect for session $sessionId: ${e.message}")
            // The worker is still stored so the caller can attach callbacks
            // and trigger manual reconnect later.
        }

        return worker
    }

    /**
     * Shuts down and removes the worker for the given session ID.
     */
    fun stopSession(sessionId: String) {
        val worker = workers.remove(sessionId)
        if (worker != null) {
            try {
                worker.shutdown()
            } catch (e: Exception) {
                System.err.println("[SshService] Error stopping session $sessionId: ${e.message}")
            }
        }
    }

    /**
     * Stops all terminal sessions that belong to the given connection.
     *
     * @param connectionId the connection whose sessions should be torn down
     * @param sessions     the list of [TerminalSession] objects that reference this connection
     */
    fun stopSessionsForConnection(connectionId: String, sessions: List<TerminalSession>) {
        for (ts in sessions) {
            if (ts.connectionId == connectionId) {
                stopSession(ts.id)
            }
        }
    }

    /**
     * Returns the worker for the given session ID, or `null` if none exists.
     */
    fun getWorker(sessionId: String): SshSessionWorker? = workers[sessionId]

    /**
     * Connects a batch of sessions with cumulative jitter between each to
     * avoid thundering-herd connection storms at application startup.
     *
     * Each successive session is scheduled approximately 100 ms after the
     * previous one, with some random jitter.
     */
    fun connectAllWithJitter(sessions: List<Pair<TerminalSession, ConnectionConfig>>) {
        var cumulativeDelay = 0L

        for ((terminalSession, config) in sessions) {
            // ~100 ms base + random 0-50 ms jitter
            val jitter = (Math.random() * 50).toLong()
            cumulativeDelay += 100 + jitter

            val sessionId = terminalSession.id
            val delaySnapshot = cumulativeDelay

            scheduler.schedule({
                try {
                    startSession(config, sessionId)
                } catch (e: Exception) {
                    System.err.println(
                        "[SshService] Startup connect failed for session $sessionId: ${e.message}"
                    )
                }
            }, delaySnapshot, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Shuts down every active worker, closes the shared [SshClient], and
     * terminates the startup scheduler.
     */
    fun shutdown() {
        // Stop the scheduler first so no new connections are started
        scheduler.shutdownNow()

        // Shut down all workers
        for ((sessionId, worker) in workers) {
            try {
                worker.shutdown()
            } catch (e: Exception) {
                System.err.println("[SshService] Error shutting down session $sessionId: ${e.message}")
            }
        }
        workers.clear()

        // Close the shared client
        try {
            sshClient.stop()
        } catch (e: Exception) {
            System.err.println("[SshService] Error stopping SshClient: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Creates and starts the shared [SshClient] with sensible defaults.
     */
    private fun createClient(): SshClient {
        val client = SshClient.setUpDefaultClient()

        // Register compression factories so they can be activated per-connection
        client.compressionFactories = listOf(
            BuiltinCompressions.none,
            BuiltinCompressions.zlib,
            BuiltinCompressions.delayedZlib
        )

        client.start()
        return client
    }
}
