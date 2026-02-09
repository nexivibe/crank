package ape.crank.ui

import ape.crank.model.ConnectionConfig
import ape.crank.ssh.SshSessionWorker
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.scene.Scene

/**
 * Status bar displayed directly below the terminal, showing connection state,
 * reconnect countdown, bytes transferred, data rate, activity status,
 * and a clickable error button when errors exist.
 */
class TerminalStatusBar : HBox(8.0) {

    private val stateCircle = Circle(5.0, Color.GRAY)
    private val stateLabel = label("#808080")
    private val hostLabel = label("#A0A0A0")
    private val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }

    /** Cached reference to the current worker, used by the error button click handler. */
    private var currentWorker: SshSessionWorker? = null

    init {
        alignment = Pos.CENTER_LEFT
        padding = Insets(3.0, 8.0, 3.0, 8.0)
        style = "-fx-background-color: #252525; -fx-border-color: #3C3C3C; -fx-border-width: 1 0 0 0;"
        minHeight = 24.0
        maxHeight = 24.0
        showNoSession()
    }

    fun showNoSession() {
        currentWorker = null
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
        currentWorker = worker

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

        // -- uptime (when connected)
        if (st == SshSessionWorker.State.CONNECTED) {
            val since = worker.connectedSince
            if (since > 0) {
                children.add(sep())
                children.add(label("#A0A0A0", "Uptime: ${formatUptime(System.currentTimeMillis() - since)}"))
            }
        }

        // -- reconnect countdown
        if (st == SshSessionWorker.State.RECONNECTING) {
            val nextMs = worker.nextReconnectTimeMs
            if (nextMs > 0) {
                val remainSec = ((nextMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                children.add(sep())
                children.add(label("#E0A030", "retry in ${remainSec}s (attempt ${worker.reconnectAttemptNumber})"))
            }
        }

        // -- error button (shown for DISCONNECTED, RECONNECTING, or CONNECTING with errors)
        val errorMsg = worker.lastErrorMessage
        val errorCount = worker.getErrorHistorySnapshot().size
        if (errorMsg != null && st != SshSessionWorker.State.CONNECTED) {
            children.add(sep())

            // Truncate the inline message to keep the status bar readable
            val shortError = if (errorMsg.length > 60) errorMsg.take(57) + "\u2026" else errorMsg
            val errorButton = Button(
                if (errorCount > 1) "\u26A0 $shortError ($errorCount errors)"
                else "\u26A0 $shortError"
            )
            errorButton.style = """
                -fx-background-color: transparent;
                -fx-text-fill: #E04040;
                -fx-font-size: 11;
                -fx-padding: 0 4 0 4;
                -fx-cursor: hand;
                -fx-border-color: #E04040;
                -fx-border-width: 1;
                -fx-border-radius: 3;
                -fx-background-radius: 3;
            """.trimIndent()
            errorButton.setOnAction { showErrorDialog(worker) }
            children.add(errorButton)
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

    // ------------------------------------------------------------------ error dialog

    private fun showErrorDialog(worker: SshSessionWorker) {
        val report = worker.getFullErrorReport()

        val textArea = TextArea(report)
        textArea.isEditable = false
        textArea.isWrapText = true
        textArea.style = """
            -fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;
            -fx-font-size: 12;
            -fx-control-inner-background: #1E1E1E;
            -fx-text-fill: #D4D4D4;
        """.trimIndent()

        val copyButton = Button("Copy to Clipboard")
        copyButton.setOnAction {
            val clipboard = Clipboard.getSystemClipboard()
            val content = ClipboardContent()
            content.putString(report)
            clipboard.setContent(content)
            copyButton.text = "Copied!"
        }

        val clearButton = Button("Clear Errors")
        clearButton.setOnAction {
            synchronized(worker.errorHistory) {
                worker.errorHistory.clear()
            }
            textArea.text = "Errors cleared."
        }

        val buttonBar = HBox(8.0, copyButton, clearButton)
        buttonBar.alignment = Pos.CENTER_RIGHT
        buttonBar.padding = Insets(8.0, 0.0, 0.0, 0.0)

        val root = BorderPane()
        root.center = textArea
        root.bottom = buttonBar
        root.padding = Insets(12.0)
        root.style = "-fx-background-color: #1E1E1E;"

        val stage = Stage()
        stage.title = "SSH Connection Errors"
        stage.initModality(Modality.APPLICATION_MODAL)
        stage.scene = Scene(root, 700.0, 500.0)
        stage.show()
    }

    /** Try to infer last data timestamp from the data rate window. */
    private fun getLastDataTimestamp(worker: SshSessionWorker): Long {
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

        fun formatUptime(ms: Long): String {
            val totalSeconds = ms / 1000
            val days = totalSeconds / 86400
            val hours = (totalSeconds % 86400) / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return buildString {
                if (days > 0) append("${days}d ")
                if (days > 0 || hours > 0) append("${hours}h ")
                if (days > 0 || hours > 0 || minutes > 0) append("${minutes}m ")
                append("${seconds}s")
            }
        }
    }
}
