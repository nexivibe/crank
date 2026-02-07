package ape.crank.ui

import ape.crank.model.ConnectionConfig
import ape.crank.ssh.SshSessionWorker
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.shape.Circle

/**
 * Status bar displayed directly below the terminal, showing connection state,
 * reconnect countdown, bytes transferred, data rate, and activity status.
 */
class TerminalStatusBar : HBox(8.0) {

    private val stateCircle = Circle(5.0, Color.GRAY)
    private val stateLabel = label("#808080")
    private val hostLabel = label("#A0A0A0")
    private val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }

    init {
        alignment = Pos.CENTER_LEFT
        padding = Insets(3.0, 8.0, 3.0, 8.0)
        style = "-fx-background-color: #252525; -fx-border-color: #3C3C3C; -fx-border-width: 1 0 0 0;"
        minHeight = 24.0
        maxHeight = 24.0
        showNoSession()
    }

    fun showNoSession() {
        children.clear()
        stateCircle.fill = Color.GRAY
        stateLabel.text = "No session selected"
        stateLabel.style = labelStyle("#808080")
        children.addAll(stateCircle, stateLabel)
    }

    fun update(
        worker: SshSessionWorker?,
        config: ConnectionConfig?,
        inactivityThresholdMs: Long
    ) {
        children.clear()

        if (worker == null || config == null) {
            showNoSession()
            return
        }

        val st = worker.currentState
        val host = "${config.username}@${config.host}${if (config.port != 22) ":${config.port}" else ""}"

        // -- state indicator + host
        when (st) {
            SshSessionWorker.State.CONNECTED -> {
                stateCircle.fill = Color.web("#4EC94E")
                stateLabel.text = "Connected"
                stateLabel.style = labelStyle("#4EC94E")
            }
            SshSessionWorker.State.CONNECTING -> {
                stateCircle.fill = Color.web("#E0C030")
                stateLabel.text = "Connecting\u2026"
                stateLabel.style = labelStyle("#E0C030")
            }
            SshSessionWorker.State.RECONNECTING -> {
                stateCircle.fill = Color.web("#E07030")
                stateLabel.text = "Reconnecting"
                stateLabel.style = labelStyle("#E07030")
            }
            SshSessionWorker.State.DISCONNECTED -> {
                stateCircle.fill = Color.web("#E04040")
                stateLabel.text = "Disconnected"
                stateLabel.style = labelStyle("#E04040")
            }
        }
        hostLabel.text = host
        children.addAll(stateCircle, stateLabel, sep(), hostLabel)

        // -- reconnect countdown
        if (st == SshSessionWorker.State.RECONNECTING) {
            val nextMs = worker.nextReconnectTimeMs
            if (nextMs > 0) {
                val remainSec = ((nextMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                children.add(sep())
                children.add(label("#E0A030", "retry in ${remainSec}s (attempt ${worker.reconnectAttemptNumber})"))
            }
        }

        // -- error message when disconnected
        val errorMsg = worker.lastErrorMessage
        if (st == SshSessionWorker.State.DISCONNECTED && errorMsg != null) {
            children.add(sep())
            children.add(label("#E04040", errorMsg))
        }

        // -- bytes transferred
        val rx = worker.bytesReceived.get()
        val tx = worker.bytesSent.get()
        if (rx > 0 || tx > 0) {
            children.add(sep())
            children.add(label("#A0A0A0", "\u2193 ${fmt(rx)}  \u2191 ${fmt(tx)}"))
        }

        // -- data rate
        val rate = worker.getRecentDataRate()
        if (st == SshSessionWorker.State.CONNECTED && rate > 1.0) {
            children.add(sep())
            children.add(label("#A0A0A0", "${fmt(rate.toLong())}/s"))
        }

        // -- activity (right-aligned)
        if (st == SshSessionWorker.State.CONNECTED) {
            children.add(spacer)
            val lastData = worker.bytesReceived.get()
            if (lastData == 0L) {
                children.add(label("#808080", "Waiting for data\u2026"))
            } else {
                val idleMs = System.currentTimeMillis() - getLastDataTimestamp(worker)
                if (idleMs > inactivityThresholdMs) {
                    children.add(label("#808080", "Idle ${idleMs / 1000}s"))
                } else {
                    children.add(label("#4EC94E", "Active"))
                }
            }
        }
    }

    /** Try to infer last data timestamp from the data rate window. */
    private fun getLastDataTimestamp(worker: SshSessionWorker): Long {
        // If rate > 0, data arrived recently; otherwise idle since we don't have a direct timestamp.
        // Use rate as a proxy: if rate > 0 in last 10s, consider active.
        return if (worker.getRecentDataRate() > 0) System.currentTimeMillis() else 0L
    }

    // ------------------------------------------------------------------ helpers

    private fun label(color: String, text: String = ""): Label =
        Label(text).apply { style = labelStyle(color); isWrapText = false }

    private fun sep(): Label =
        Label("|").apply { style = labelStyle("#505050") }

    private fun labelStyle(color: String) = "-fx-text-fill: $color; -fx-font-size: 11;"

    companion object {
        fun fmt(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
