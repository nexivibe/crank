package ape.crank.ssh

import ape.crank.model.ConnectionConfig
import ape.crank.model.TerminalSession
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.core.CoreModuleProperties
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-wide SSH service that owns the single [SshClient] instance
 * and manages all [SshSessionWorker] instances keyed by session ID.
 */
class SshService {

    private val sshClient: SshClient = createClient()
    private val workers = ConcurrentHashMap<String, SshSessionWorker>()

    /**
     * Creates a new [SshSessionWorker] and stores it.
     * Does NOT connect — the caller must set callbacks first, then call
     * [SshSessionWorker.connectAsync] or [SshSessionWorker.connect].
     */
    fun createSession(config: ConnectionConfig, sessionId: String): SshSessionWorker {
        workers[sessionId]?.shutdown()
        val worker = SshSessionWorker(sshClient, config, sessionId)
        workers[sessionId] = worker
        return worker
    }

    fun stopSession(sessionId: String) {
        workers.remove(sessionId)?.shutdown()
    }

    fun stopSessionsForConnection(connectionId: String, sessions: List<TerminalSession>) {
        for (ts in sessions) {
            if (ts.connectionId == connectionId) {
                stopSession(ts.id)
            }
        }
    }

    fun getWorker(sessionId: String): SshSessionWorker? = workers[sessionId]

    fun shutdown() {
        for ((_, worker) in workers) {
            try { worker.shutdown() } catch (_: Exception) {}
        }
        workers.clear()
        try { sshClient.stop() } catch (_: Exception) {}
    }

    private fun createClient(): SshClient {
        val client = SshClient.setUpDefaultClient()
        client.compressionFactories = listOf(
            BuiltinCompressions.none,
            BuiltinCompressions.zlib,
            BuiltinCompressions.delayedZlib
        )

        // Enable TCP keepalive on the socket — matches OpenSSH's default TCPKeepAlive=yes.
        // This lets the OS detect dead connections through NAT/firewalls, which is the most
        // likely reason Crank drops connections while GNOME Terminal (OpenSSH) doesn't.
        try {
            CoreModuleProperties.SOCKET_KEEPALIVE.set(client, true)
        } catch (e: Exception) {
            System.err.println("[SshService] failed to set TCP keepalive: ${e.message}")
        }

        // Disable MINA SSHD's default 10-minute idle timeout. The default causes sessions
        // to be closed after ~10 minutes of inactivity at the NIO layer, even when
        // SSH_MSG_IGNORE heartbeats are configured per-session. Crank manages connection
        // liveness via heartbeats and auto-reconnect, so this client-level timeout is
        // unnecessary and actively harmful.
        try {
            CoreModuleProperties.IDLE_TIMEOUT.set(client, java.time.Duration.ZERO)
        } catch (e: Exception) {
            System.err.println("[SshService] failed to disable idle timeout: ${e.message}")
        }

        client.start()
        return client
    }
}
