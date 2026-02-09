package ape.crank.ui

import ape.crank.config.ConnectionLogger
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
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Duration
import java.util.UUID
import java.util.Timer
import java.util.TimerTask

class MainWindow(private val stateManager: StateManager) {

    // ------------------------------------------------------------------ services

    val sshService = SshService()

    // ------------------------------------------------------------------ per-session terminal state

    private val terminalBuffers = mutableMapOf<String, TerminalBuffer>()
    private val terminalParsers = mutableMapOf<String, VT100Parser>()

    // ------------------------------------------------------------------ UI components

    private val sessionTreeView = SessionTreeView()
    private val terminalWidget = TerminalWidget()
    private val terminalStatusBar = TerminalStatusBar()
    private val clipboardToggle = ToggleButton("Clipboard", CrankIcons.icon(CrankIcons.CLIPBOARD, size = 14.0)).apply {
        style = "-fx-font-size: 11;"
        isSelected = true
        selectedProperty().addListener { _, _, newVal ->
            terminalWidget.localClipboardMode = newVal
        }
    }
    private val placeholderLabel = Label("Select or create a terminal session").apply {
        style = "-fx-text-fill: #808080; -fx-font-size: 16;"
    }
    private val terminalPane = StackPane(placeholderLabel)
    private val terminalScrollBar = ScrollBar().apply {
        orientation = Orientation.VERTICAL
        prefWidth = 14.0
        min = 0.0
        max = 0.0
        value = 0.0
        visibleAmount = 1.0
        unitIncrement = 1.0
        blockIncrement = 24.0
    }
    private val missionTextField = TextField().apply {
        promptText = "Mission notes for this session..."
        style = "-fx-background-color: #2A2A2A; -fx-text-fill: #D0D0D0; -fx-prompt-text-fill: #606060; -fx-border-color: #3A3A3A; -fx-border-width: 0 0 1 0;"
        isVisible = false
        isManaged = false
    }
    private val rightPane = BorderPane()
    private val splitPane = SplitPane()
    private val globalStatusBar = HBox(10.0)
    private val connectionCountLabel = Label("Connections: 0")
    private val activeCountLabel = Label("Active: 0")
    private val menuBar = MenuBar()

    // ------------------------------------------------------------------ tracking

    private var currentSessionId: String? = null
    private val sessionStateCache = mutableMapOf<String, SshSessionWorker.State>()
    private val sessionLastDataTimestamp = mutableMapOf<String, Long>()
    private var activityTimeline: Timeline? = null
    private var stage: Stage? = null
    private val missionChangeListener = javafx.beans.value.ChangeListener<String> { _, _, newValue ->
        val sid = currentSessionId ?: return@ChangeListener
        val session = stateManager.state.sessions.find { it.id == sid } ?: return@ChangeListener
        session.mission = newValue ?: ""
        stateManager.saveLater()
    }

    // ------------------------------------------------------------------ init

    init {
        initMenuBar()
        initSessionTree()
        initTerminalWidget()
        initRightPane()
        initSplitPane()
        initGlobalStatusBar()
        initStatusUpdateLoop()

        Platform.runLater {
            val lastId = stateManager.state.lastSelectedSessionId
            if (lastId != null && stateManager.state.sessions.any { it.id == lastId }) {
                selectSession(lastId)
            }
        }

        connectAllPersistedSessions()
        sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        updateGlobalStatusBar()
    }

    // ------------------------------------------------------------------ scene builder

    fun buildScene(stage: Stage): Scene {
        this.stage = stage

        val root = BorderPane()
        root.top = menuBar
        root.center = splitPane
        root.bottom = globalStatusBar

        val state = stateManager.state
        val scene = Scene(root, state.windowWidth, state.windowHeight)

        root.style = "-fx-background-color: #1E1E1E;"
        globalStatusBar.style = "-fx-background-color: #2D2D2D; -fx-padding: 4 8 4 8;"
        connectionCountLabel.style = "-fx-text-fill: #A0A0A0; -fx-font-size: 11;"
        activeCountLabel.style = "-fx-text-fill: #A0A0A0; -fx-font-size: 11;"

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
        sessionTreeView.onSessionSelected = { session -> selectSession(session.id) }
        sessionTreeView.onNewTerminalRequested = { folderId -> createNewTerminal(folderId) }
        sessionTreeView.onNewFolderRequested = { createNewFolder() }
        sessionTreeView.onSessionRemoved = { session -> removeSession(session) }
        sessionTreeView.onSessionRenamed = { _ ->
            stateManager.save()
        }
        sessionTreeView.onFolderRenamed = { _ ->
            stateManager.save()
        }
        sessionTreeView.onFolderRemoved = { folder ->
            stateManager.state.folders.removeIf { it.id == folder.id }
            stateManager.save()
            sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        }
        sessionTreeView.onSessionMoved = { sessionId, newFolderId ->
            val session = stateManager.state.sessions.find { it.id == sessionId }
            if (session != null) {
                session.folderId = newFolderId
                stateManager.save()
                sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
            }
        }
    }

    // ------------------------------------------------------------------ terminal widget

    private var updatingScrollBar = false

    private fun initTerminalWidget() {
        terminalWidget.onInput = label@{ data ->
            val sid = currentSessionId ?: return@label
            sshService.getWorker(sid)?.sendData(data)
        }
        terminalWidget.onResize = label@{ cols, rows ->
            val sid = currentSessionId ?: return@label
            // Resize the buffer (already done by handleLayoutResize on the attached buffer,
            // but the worker needs the PTY signal)
            sshService.getWorker(sid)?.resize(cols, rows)
        }
        terminalWidget.onScrollChanged = { offset, max ->
            Platform.runLater {
                updatingScrollBar = true
                terminalScrollBar.max = max.toDouble()
                // Invert: scrollbar 0 = top of scrollback, max = live bottom
                terminalScrollBar.value = (max - offset).toDouble()
                terminalScrollBar.visibleAmount = terminalWidget.termRows.toDouble()
                updatingScrollBar = false
            }
        }
        terminalScrollBar.valueProperty().addListener { _, _, newVal ->
            if (!updatingScrollBar) {
                val max = terminalScrollBar.max.toInt()
                val offset = max - newVal.toInt()
                terminalWidget.scrollTo(offset)
            }
        }
    }

    // ------------------------------------------------------------------ right pane (terminal + status bar)

    private fun initRightPane() {
        terminalPane.children.add(terminalWidget)
        terminalWidget.isVisible = false

        val terminalWithScrollbar = HBox().apply {
            children.addAll(terminalPane, terminalScrollBar)
            HBox.setHgrow(terminalPane, Priority.ALWAYS)
        }

        val terminalWithMission = VBox().apply {
            children.addAll(missionTextField, terminalWithScrollbar)
            VBox.setVgrow(terminalWithScrollbar, Priority.ALWAYS)
        }

        rightPane.center = terminalWithMission
        val bottomBar = HBox(8.0, clipboardToggle, terminalStatusBar).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(terminalStatusBar, Priority.ALWAYS)
        }
        rightPane.bottom = bottomBar
    }

    // ------------------------------------------------------------------ split pane

    private fun initSplitPane() {
        val leftScrollPane = ScrollPane(sessionTreeView).apply {
            isFitToWidth = true
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }

        splitPane.orientation = Orientation.HORIZONTAL
        splitPane.items.addAll(leftScrollPane, rightPane)

        Platform.runLater {
            if (splitPane.dividers.isNotEmpty()) {
                splitPane.dividers[0].position = stateManager.state.dividerPosition
                splitPane.dividers[0].positionProperty().addListener { _, _, newVal ->
                    stateManager.state.dividerPosition = newVal.toDouble()
                    stateManager.saveLater()
                }
            }
        }
    }

    // ------------------------------------------------------------------ global status bar

    private fun initGlobalStatusBar() {
        globalStatusBar.alignment = Pos.CENTER_LEFT
        globalStatusBar.padding = Insets(4.0, 8.0, 4.0, 8.0)
        globalStatusBar.children.addAll(connectionCountLabel, activeCountLabel)
    }

    // ------------------------------------------------------------------ periodic status updates

    private fun initStatusUpdateLoop() {
        val timeline = Timeline(
            KeyFrame(Duration.millis(500.0), {
                updateTerminalStatusBar()
                checkActivityAndUpdateTree()
                updateGlobalStatusBar()
                updateWindowTitle()
                updateBandwidthList()
            })
        )
        timeline.cycleCount = Timeline.INDEFINITE
        timeline.play()
        activityTimeline = timeline
    }

    private fun updateTerminalStatusBar() {
        val sid = currentSessionId
        if (sid == null) {
            terminalStatusBar.showNoSession()
            return
        }
        val worker = sshService.getWorker(sid)
        val config = stateManager.state.sessions.find { it.id == sid }?.let { session ->
            stateManager.state.connections.find { it.id == session.connectionId }
        }
        terminalStatusBar.update(worker, config, stateManager.state.inactivityThresholdSeconds * 1000L)
    }

    private fun checkActivityAndUpdateTree() {
        val thresholdMs = stateManager.state.inactivityThresholdSeconds * 1000L
        for (session in stateManager.state.sessions) {
            val state = sessionStateCache[session.id] ?: SshSessionWorker.State.DISCONNECTED
            val inactive = isSessionInactive(session.id, thresholdMs)
            sessionTreeView.updateSessionStatus(session.id, state, inactive)
        }
    }

    private fun isSessionInactive(sessionId: String, thresholdMs: Long): Boolean {
        val timestamp = sessionLastDataTimestamp[sessionId] ?: return true
        return (System.currentTimeMillis() - timestamp) > thresholdMs
    }

    private fun updateGlobalStatusBar() {
        val totalConnections = stateManager.state.connections.size
        val activeSessions = stateManager.state.sessions.count { session ->
            sessionStateCache[session.id] == SshSessionWorker.State.CONNECTED
        }
        connectionCountLabel.text = "Connections: $totalConnections"
        activeCountLabel.text = "Active: $activeSessions / ${stateManager.state.sessions.size}"
    }

    private fun updateBandwidthList() {
        val entries = stateManager.state.sessions.map { session ->
            val worker = sshService.getWorker(session.id)
            val state = sessionStateCache[session.id] ?: SshSessionWorker.State.DISCONNECTED
            val rate = worker?.getRecentDataRate() ?: 0.0
            SessionTreeView.BandwidthEntry(
                sessionId = session.id,
                sessionName = session.name,
                state = state,
                bytesPerSecond = rate
            )
        }
        sessionTreeView.updateBandwidthList(entries)
    }

    private fun updateWindowTitle() {
        val total = stateManager.state.sessions.size
        val active = stateManager.state.sessions.count { session ->
            sessionStateCache[session.id] == SshSessionWorker.State.CONNECTED
        }
        val title = if (total > 0) "Crank [$active/$total Active]" else "Crank"
        stage?.title = title
    }

    // ------------------------------------------------------------------ public methods

    fun openConnectionsDialog() {
        val sessionCountsByConnection = stateManager.state.sessions.groupBy { it.connectionId }
            .mapValues { (_, sessions) -> sessions.size }
        val dialog = ConnectionDialog(
            stateManager.state.connections,
            sessionCountsByConnection
        ) { removedConnection ->
            val affectedSessions = stateManager.state.sessions.filter { it.connectionId == removedConnection.id }
            sshService.stopSessionsForConnection(removedConnection.id, affectedSessions)
            for (session in affectedSessions) { cleanupSession(session.id) }
            stateManager.state.sessions.removeAll(affectedSessions.toSet())
            stateManager.save()
            sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
            updateGlobalStatusBar()
        }
        dialog.showAndWait()
        stateManager.save()
        updateGlobalStatusBar()
    }

    fun createNewTerminal(folderId: String? = null) {
        if (stateManager.state.connections.isEmpty()) {
            Alert(Alert.AlertType.INFORMATION).apply {
                title = "No Connections"
                headerText = "No connections configured"
                contentText = "Please add a connection in Settings > Connections first."
            }.showAndWait()
            return
        }

        val dialog = NewTerminalDialog(stateManager.state.connections)
        val result = dialog.showAndWait()
        val session: TerminalSession? = if (result.isPresent) result.get() else null
        if (session == null) return

        session.folderId = folderId
        session.order = stateManager.state.sessions.size
        stateManager.state.sessions.add(session)
        stateManager.save()

        val config = stateManager.state.connections.find { it.id == session.connectionId } ?: return

        // Create buffer and parser
        val buffer = TerminalBuffer(terminalWidget.termCols, terminalWidget.termRows)
        val parser = VT100Parser(buffer)
        terminalBuffers[session.id] = buffer
        terminalParsers[session.id] = parser

        // Create worker, set callbacks FIRST, set PTY size, then connect asynchronously
        val worker = sshService.createSession(config, session.id)
        setupSshCallbacks(session, worker)
        worker.resize(terminalWidget.termCols, terminalWidget.termRows)
        worker.connectAsync()

        sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        selectSession(session.id)
        updateGlobalStatusBar()
    }

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

    fun selectSession(sessionId: String) {
        val session = stateManager.state.sessions.find { it.id == sessionId } ?: return

        terminalWidget.detachSession()
        currentSessionId = sessionId
        stateManager.state.lastSelectedSessionId = sessionId
        stateManager.saveLater()

        val buffer = terminalBuffers.getOrPut(sessionId) {
            TerminalBuffer(terminalWidget.termCols, terminalWidget.termRows)
        }
        // Resize buffer if widget dimensions differ (e.g. startup used 80x24)
        if (buffer.cols != terminalWidget.termCols || buffer.rows != terminalWidget.termRows) {
            buffer.resize(terminalWidget.termCols, terminalWidget.termRows)
        }
        // Always notify the server of current PTY dimensions on session switch
        sshService.getWorker(sessionId)?.resize(terminalWidget.termCols, terminalWidget.termRows)
        val parser = terminalParsers.getOrPut(sessionId) { VT100Parser(buffer) }

        terminalWidget.attachSession(buffer, parser)
        terminalWidget.isVisible = true
        placeholderLabel.isVisible = false

        // Show mission field and populate from session
        missionTextField.textProperty().removeListener(missionChangeListener)
        missionTextField.text = session.mission
        missionTextField.isVisible = true
        missionTextField.isManaged = true
        missionTextField.textProperty().addListener(missionChangeListener)

        // Reset scrollbar for this session (start at live/bottom view)
        updatingScrollBar = true
        terminalScrollBar.max = buffer.scrollback.size.toDouble()
        terminalScrollBar.value = buffer.scrollback.size.toDouble()
        terminalScrollBar.visibleAmount = terminalWidget.termRows.toDouble()
        terminalScrollBar.blockIncrement = terminalWidget.termRows.toDouble()
        updatingScrollBar = false

        sessionTreeView.selectSession(sessionId)
        updateTerminalStatusBar()

        Platform.runLater { terminalWidget.requestFocus() }
    }

    /**
     * Wire the SSH worker's callbacks. MUST be called BEFORE connect/connectAsync.
     */
    fun setupSshCallbacks(session: TerminalSession, worker: SshSessionWorker) {
        worker.onData = { data ->
            sessionLastDataTimestamp[session.id] = System.currentTimeMillis()

            Platform.runLater {
                if (currentSessionId == session.id) {
                    terminalWidget.feedData(data)
                } else {
                    terminalParsers[session.id]?.feed(data)
                }
            }
        }

        worker.onStateChanged = { newState ->
            sessionStateCache[session.id] = newState
            // Reset parser state machine on reconnection to prevent garbled
            // output from stale partial escape sequences
            if (newState == SshSessionWorker.State.CONNECTED) {
                terminalParsers[session.id]?.reset()
                ConnectionLogger.log("active", session.name, session.id)
            } else if (newState == SshSessionWorker.State.DISCONNECTED) {
                ConnectionLogger.log("inactive", session.name, session.id)
            }
            val thresholdMs = stateManager.state.inactivityThresholdSeconds * 1000L
            val inactive = isSessionInactive(session.id, thresholdMs)
            Platform.runLater {
                sessionTreeView.updateSessionStatus(session.id, newState, inactive)
                updateGlobalStatusBar()
                if (currentSessionId == session.id) {
                    updateTerminalStatusBar()
                }
            }
        }

        terminalParsers[session.id]?.onTitleChanged = { title ->
            Platform.runLater {
                sessionTreeView.updateSessionTitle(session.id, title)
            }
        }

        terminalParsers[session.id]?.onDeviceStatusResponse = { response ->
            worker.sendData(response)
        }
    }

    fun removeSession(session: TerminalSession) {
        sshService.stopSession(session.id)
        cleanupSession(session.id)
        stateManager.state.sessions.removeIf { it.id == session.id }
        stateManager.save()

        if (currentSessionId == session.id) {
            terminalWidget.detachSession()
            terminalWidget.isVisible = false
            placeholderLabel.isVisible = true
            missionTextField.isVisible = false
            missionTextField.isManaged = false
            currentSessionId = null
            stateManager.state.lastSelectedSessionId = null
            terminalStatusBar.showNoSession()
        }

        sessionTreeView.refresh(stateManager.state.sessions, stateManager.state.folders)
        updateGlobalStatusBar()
    }

    private fun cleanupSession(sessionId: String) {
        terminalBuffers.remove(sessionId)
        terminalParsers.remove(sessionId)
        sessionStateCache.remove(sessionId)
        sessionLastDataTimestamp.remove(sessionId)
    }

    fun shutdown() {
        activityTimeline?.stop()
        sshService.shutdown()
        stateManager.save()
    }

    // ------------------------------------------------------------------ startup reconnection

    private fun connectAllPersistedSessions() {
        val sessionsToConnect = mutableListOf<Pair<TerminalSession, ConnectionConfig>>()

        for (session in stateManager.state.sessions) {
            val config = stateManager.state.connections.find { it.id == session.connectionId } ?: continue

            val buffer = TerminalBuffer(80, 24)
            val parser = VT100Parser(buffer)
            terminalBuffers[session.id] = buffer
            terminalParsers[session.id] = parser

            // Create worker and set callbacks BEFORE any connection attempt
            val worker = sshService.createSession(config, session.id)
            setupSshCallbacks(session, worker)
            // Pre-set PTY size so connect() uses actual widget dimensions (if available)
            // rather than the 80x24 default
            worker.resize(terminalWidget.termCols, terminalWidget.termRows)

            sessionsToConnect.add(Pair(session, config))
        }

        if (sessionsToConnect.isEmpty()) return

        // Stagger connections with ~100ms jitter
        var cumulativeDelay = 0L
        val timer = Timer("startup-connect", true)
        for ((session, _) in sessionsToConnect) {
            val jitter = (Math.random() * 50).toLong()
            cumulativeDelay += 100 + jitter
            val delaySnapshot = cumulativeDelay

            timer.schedule(object : TimerTask() {
                override fun run() {
                    sshService.getWorker(session.id)?.connectAsync()
                }
            }, delaySnapshot)
        }
    }
}
