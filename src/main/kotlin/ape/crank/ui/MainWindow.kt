package ape.crank.ui

import ape.crank.config.StateManager
import ape.crank.model.ConnectionConfig
import ape.crank.model.SessionFolder
import ape.crank.model.TerminalSession
import ape.crank.ssh.SshService
import ape.crank.ssh.SshSessionWorker
import ape.crank.terminal.TerminalBuffer
import ape.crank.terminal.TerminalWidget
import ape.crank.terminal.VT100Parser
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration
import java.util.UUID

/**
 * The main application window layout for Crank.
 *
 * Assembles the menu bar, split pane (session tree + terminal), and status bar.
 * Coordinates SSH connections, terminal buffers/parsers, and state persistence.
 */
class MainWindow(private val stateManager: StateManager) {

    // ------------------------------------------------------------------ services

    val sshService = SshService()

    // ------------------------------------------------------------------ per-session terminal state

    private val terminalBuffers = mutableMapOf<String, TerminalBuffer>()
    private val terminalParsers = mutableMapOf<String, VT100Parser>()

    // ------------------------------------------------------------------ UI components

    private val sessionTreeView = SessionTreeView()
    private val terminalWidget = TerminalWidget()
    private val placeholderLabel = Label("Select or create a terminal session").apply {
        style = "-fx-text-fill: #808080; -fx-font-size: 16;"
    }
    private val terminalPane = StackPane(placeholderLabel)
    private val splitPane = SplitPane()
    private val statusBar = HBox(10.0)
    private val connectionCountLabel = Label("Connections: 0")
    private val activeCountLabel = Label("Active: 0")
    private val menuBar = MenuBar()

    // ------------------------------------------------------------------ tracking

    private var currentSessionId: String? = null
    private var activityTimeline: Timeline? = null

    // ------------------------------------------------------------------ init

    init {
        initMenuBar()
        initSessionTree()
        initTerminalWidget()
        initSplitPane()
        initStatusBar()
        initActivityMonitor()

        // Restore last selected session
        val lastId = stateManager.state.lastSelectedSessionId
        if (lastId != null && stateManager.state.sessions.any { it.id == lastId }) {
            Platform.runLater { selectSession(lastId) }
        }

        // Connect all persisted sessions with jitter
        connectAllPersistedSessions()

        // Initial tree refresh
        sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        updateStatusBar()
    }

    // ------------------------------------------------------------------ scene builder

    /**
     * Build and return the fully configured [Scene]. Also wires up stage-level
     * listeners for window size persistence.
     */
    fun buildScene(stage: Stage): Scene {
        val root = BorderPane()
        root.top = menuBar
        root.center = splitPane
        root.bottom = statusBar

        val state = stateManager.state
        val scene = Scene(root, state.windowWidth, state.windowHeight)

        // Dark theme baseline
        root.style = "-fx-background-color: #1E1E1E;"
        statusBar.style = "-fx-background-color: #2D2D2D; -fx-padding: 4 8 4 8;"
        connectionCountLabel.style = "-fx-text-fill: #A0A0A0; -fx-font-size: 11;"
        activeCountLabel.style = "-fx-text-fill: #A0A0A0; -fx-font-size: 11;"

        // Persist window size on change
        stage.widthProperty().addListener { _, _, newVal ->
            stateManager.state.windowWidth = newVal.toDouble()
            stateManager.saveLater()
        }
        stage.heightProperty().addListener { _, _, newVal ->
            stateManager.state.windowHeight = newVal.toDouble()
            stateManager.saveLater()
        }

        return scene
    }

    // ------------------------------------------------------------------ menu bar

    private fun initMenuBar() {
        val settingsMenu = Menu("Settings")
        val connectionsItem = MenuItem("Connections...")
        connectionsItem.setOnAction { openConnectionsDialog() }
        settingsMenu.items.add(connectionsItem)
        menuBar.menus.add(settingsMenu)
    }

    // ------------------------------------------------------------------ session tree

    private fun initSessionTree() {
        sessionTreeView.onSessionSelected = { session ->
            selectSession(session.id)
        }

        sessionTreeView.onNewTerminalRequested = {
            createNewTerminal()
        }

        sessionTreeView.onNewFolderRequested = {
            createNewFolder()
        }

        sessionTreeView.onSessionRemoved = { session ->
            removeSession(session)
        }
    }

    // ------------------------------------------------------------------ terminal widget

    private fun initTerminalWidget() {
        // onInput: forward keystrokes to the SSH worker for the current session
        terminalWidget.onInput = label@{ data ->
            val sid = currentSessionId ?: return@label
            sshService.getWorker(sid)?.sendData(data)
        }

        // onResize: notify the SSH worker and resize the buffer
        terminalWidget.onResize = label@{ cols, rows ->
            val sid = currentSessionId ?: return@label
            sshService.getWorker(sid)?.resize(cols, rows)
            terminalBuffers[sid]?.resize(cols, rows)
        }
    }

    // ------------------------------------------------------------------ split pane

    private fun initSplitPane() {
        terminalPane.children.add(terminalWidget)
        // Placeholder is on top when no session is selected; terminal underneath
        // We manage visibility to show/hide them
        terminalWidget.isVisible = false

        val leftScrollPane = ScrollPane(sessionTreeView).apply {
            isFitToWidth = true
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }

        splitPane.orientation = Orientation.HORIZONTAL
        splitPane.items.addAll(leftScrollPane, terminalPane)

        // Restore divider position
        Platform.runLater {
            if (splitPane.dividers.isNotEmpty()) {
                splitPane.dividers[0].position = stateManager.state.dividerPosition
            }
        }

        // Persist divider position on change
        splitPane.dividers.forEach { divider ->
            divider.positionProperty().addListener { _, _, newVal ->
                stateManager.state.dividerPosition = newVal.toDouble()
                stateManager.saveLater()
            }
        }

        // Re-attach listener after dividers are realized (they may not exist until layout)
        splitPane.needsLayoutProperty().addListener { _, _, _ ->
            if (splitPane.dividers.isNotEmpty()) {
                splitPane.dividers[0].positionProperty().addListener { _, _, newVal ->
                    stateManager.state.dividerPosition = newVal.toDouble()
                    stateManager.saveLater()
                }
            }
        }
    }

    // ------------------------------------------------------------------ status bar

    private fun initStatusBar() {
        statusBar.alignment = Pos.CENTER_LEFT
        statusBar.padding = Insets(4.0, 8.0, 4.0, 8.0)
        statusBar.children.addAll(connectionCountLabel, activeCountLabel)
    }

    // ------------------------------------------------------------------ activity monitor

    private fun initActivityMonitor() {
        val timeline = Timeline(
            KeyFrame(Duration.seconds(5.0), {
                checkActivityAndUpdateTree()
                updateStatusBar()
            })
        )
        timeline.cycleCount = Timeline.INDEFINITE
        timeline.play()
        activityTimeline = timeline
    }

    private fun checkActivityAndUpdateTree() {
        val thresholdMs = stateManager.state.inactivityThresholdSeconds * 1000L
        for (session in stateManager.state.sessions) {
            // Determine connection state from cached callbacks
            val state = sessionStateCache[session.id] ?: SshSessionWorker.State.DISCONNECTED

            // Check inactivity via the widget if this session is currently displayed,
            // otherwise use the buffer's last-feed timestamp tracked per session
            val inactive = isSessionInactive(session.id, thresholdMs)

            sessionTreeView.updateSessionStatus(session.id, state, inactive)
        }
    }

    /** Cache of session state set by onStateChanged callbacks. */
    private val sessionStateCache = mutableMapOf<String, SshSessionWorker.State>()

    /** Per-session last data timestamp (tracked even when the session is not displayed). */
    private val sessionLastDataTimestamp = mutableMapOf<String, Long>()

    private fun isSessionInactive(sessionId: String, thresholdMs: Long): Boolean {
        val timestamp = sessionLastDataTimestamp[sessionId] ?: return true
        return (System.currentTimeMillis() - timestamp) > thresholdMs
    }

    private fun updateStatusBar() {
        val totalConnections = stateManager.state.connections.size
        val activeSessions = stateManager.state.sessions.count { session ->
            val cachedState = sessionStateCache[session.id]
            cachedState == SshSessionWorker.State.CONNECTED
        }
        connectionCountLabel.text = "Connections: $totalConnections"
        activeCountLabel.text = "Active: $activeSessions"
    }

    // ------------------------------------------------------------------ public methods

    /**
     * Opens the connection management dialog.
     */
    fun openConnectionsDialog() {
        val openSessionIds = stateManager.state.sessions.map { it.connectionId }.toSet()
        val dialog = ConnectionDialog(
            stateManager.state.connections,
            openSessionIds
        ) { removedConnection ->
            // When a connection is removed, stop all sessions that use it
            val affectedSessions = stateManager.state.sessions.filter { it.connectionId == removedConnection.id }
            sshService.stopSessionsForConnection(removedConnection.id, affectedSessions)
            for (session in affectedSessions) {
                cleanupSession(session.id)
            }
            stateManager.state.sessions.removeAll(affectedSessions.toSet())
            stateManager.save()
            sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
            updateStatusBar()
        }
        dialog.showAndWait()
        // After dialog closes, save any edits
        stateManager.save()
        updateStatusBar()
    }

    /**
     * Opens the new terminal dialog, creates the session, and starts the SSH connection.
     */
    fun createNewTerminal() {
        if (stateManager.state.connections.isEmpty()) {
            val alert = Alert(Alert.AlertType.INFORMATION)
            alert.title = "No Connections"
            alert.headerText = "No connections configured"
            alert.contentText = "Please add a connection in Settings > Connections first."
            alert.showAndWait()
            return
        }

        val dialog = NewTerminalDialog(stateManager.state.connections)
        val optionalResult = dialog.showAndWait()
        val session: TerminalSession? = if (optionalResult.isPresent) optionalResult.get() else null

        if (session != null) {
            session.order = stateManager.state.sessions.size
            stateManager.state.sessions.add(session)
            stateManager.save()

            // Find the connection config for this session
            val config = stateManager.state.connections.find { it.id == session.connectionId } ?: return

            // Create buffer and parser
            val buffer = TerminalBuffer(terminalWidget.termCols, terminalWidget.termRows)
            val parser = VT100Parser(buffer)
            terminalBuffers[session.id] = buffer
            terminalParsers[session.id] = parser

            // Start SSH
            val worker = sshService.startSession(config, session.id)
            setupSshCallbacks(session, worker)

            // Refresh tree and select the new session
            sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
            selectSession(session.id)
            updateStatusBar()
        }
    }

    /**
     * Create a new folder and add it to the state.
     */
    private fun createNewFolder() {
        val dialog = TextInputDialog("New Folder")
        dialog.title = "New Folder"
        dialog.headerText = "Enter a name for the new folder:"
        dialog.contentText = "Name:"
        val result = dialog.showAndWait()
        if (result.isPresent && result.get().isNotBlank()) {
            val folder = SessionFolder(
                id = UUID.randomUUID().toString(),
                name = result.get().trim(),
                order = stateManager.state.folders.size
            )
            stateManager.state.folders.add(folder)
            stateManager.save()
            sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        }
    }

    /**
     * Switch the terminal widget to display the given session.
     */
    fun selectSession(sessionId: String) {
        stateManager.state.sessions.find { it.id == sessionId } ?: return

        // Detach from previous session
        terminalWidget.detachSession()

        currentSessionId = sessionId
        stateManager.state.lastSelectedSessionId = sessionId
        stateManager.saveLater()

        // Ensure buffer and parser exist
        val buffer = terminalBuffers.getOrPut(sessionId) {
            TerminalBuffer(terminalWidget.termCols, terminalWidget.termRows)
        }
        val parser = terminalParsers.getOrPut(sessionId) {
            VT100Parser(buffer)
        }

        // Attach to widget
        terminalWidget.attachSession(buffer, parser)
        terminalWidget.isVisible = true
        placeholderLabel.isVisible = false

        // Update tree selection
        sessionTreeView.selectSession(sessionId)

        // Focus the terminal
        Platform.runLater { terminalWidget.requestFocus() }
    }

    /**
     * Wire the SSH worker's callbacks to feed data into the terminal buffer
     * and update the tree's status indicator.
     */
    fun setupSshCallbacks(session: TerminalSession, worker: SshSessionWorker) {
        worker.onData = { data ->
            // Track per-session activity timestamp
            sessionLastDataTimestamp[session.id] = System.currentTimeMillis()

            Platform.runLater {
                if (currentSessionId == session.id) {
                    // Currently displayed: use feedData which parses, tracks activity, and renders
                    terminalWidget.feedData(data)
                } else {
                    // Background session: parse directly into the buffer for later display
                    terminalParsers[session.id]?.feed(data)
                }
            }
        }

        worker.onStateChanged = { state ->
            sessionStateCache[session.id] = state
            val thresholdMs = stateManager.state.inactivityThresholdSeconds * 1000L
            val inactive = isSessionInactive(session.id, thresholdMs)
            Platform.runLater {
                sessionTreeView.updateSessionStatus(session.id, state, inactive)
                updateStatusBar()
            }
        }

        // Wire DSR responses back to the SSH channel
        val parser = terminalParsers[session.id]
        parser?.onDeviceStatusResponse = { response ->
            worker.sendData(response)
        }
    }

    /**
     * Remove a session: stop SSH, clean up buffers, remove from state, refresh tree.
     */
    fun removeSession(session: TerminalSession) {
        sshService.stopSession(session.id)
        cleanupSession(session.id)

        stateManager.state.sessions.removeIf { it.id == session.id }
        stateManager.save()

        // If this was the displayed session, detach
        if (currentSessionId == session.id) {
            terminalWidget.detachSession()
            terminalWidget.isVisible = false
            placeholderLabel.isVisible = true
            currentSessionId = null
            stateManager.state.lastSelectedSessionId = null
        }

        sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        updateStatusBar()
    }

    /**
     * Clean up buffer, parser, and state caches for a session.
     */
    private fun cleanupSession(sessionId: String) {
        terminalBuffers.remove(sessionId)
        terminalParsers.remove(sessionId)
        sessionStateCache.remove(sessionId)
        sessionLastDataTimestamp.remove(sessionId)
    }

    /**
     * Shut down all SSH connections, stop the activity monitor, and save state.
     */
    fun shutdown() {
        activityTimeline?.stop()
        sshService.shutdown()
        stateManager.save()
    }

    // ------------------------------------------------------------------ startup reconnection

    /**
     * Reconnect all persisted sessions at startup using jitter to avoid thundering herd.
     */
    private fun connectAllPersistedSessions() {
        val sessionsToConnect = mutableListOf<Pair<TerminalSession, ConnectionConfig>>()

        for (session in stateManager.state.sessions) {
            val config = stateManager.state.connections.find { it.id == session.connectionId }
            if (config != null) {
                // Pre-create buffer and parser so callbacks can feed data immediately
                val buffer = TerminalBuffer(80, 24)
                val parser = VT100Parser(buffer)
                terminalBuffers[session.id] = buffer
                terminalParsers[session.id] = parser

                sessionsToConnect.add(Pair(session, config))
            }
        }

        if (sessionsToConnect.isEmpty()) return

        // Use connectAllWithJitter which calls startSession internally.
        // We need to set up callbacks after each worker is created.
        // Since connectAllWithJitter creates workers via startSession on a scheduled executor,
        // we set up callbacks by polling for worker existence after a delay.
        // A cleaner approach: start sessions individually with callbacks.

        // We'll connect each session manually with jitter ourselves,
        // setting up callbacks before connection.
        var cumulativeDelay = 0L
        val timer = java.util.Timer("startup-connect", true)
        for ((session, config) in sessionsToConnect) {
            val jitter = (Math.random() * 50).toLong()
            cumulativeDelay += 100 + jitter
            val delaySnapshot = cumulativeDelay

            timer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        val worker = sshService.startSession(config, session.id)
                        Platform.runLater {
                            setupSshCallbacks(session, worker)
                        }
                    } catch (e: Exception) {
                        System.err.println("[MainWindow] Startup connect failed for ${session.id}: ${e.message}")
                        // The worker is still stored; set up callbacks so reconnect data flows
                        Platform.runLater {
                            val worker = sshService.getWorker(session.id)
                            if (worker != null) {
                                setupSshCallbacks(session, worker)
                            }
                        }
                    }
                }
            }, delaySnapshot)
        }
    }
}
