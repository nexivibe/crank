package ape.crank.ui

import ape.crank.model.SessionFolder
import ape.crank.model.TerminalSession
import ape.crank.ssh.SshSessionWorker
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.TransferMode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Left panel hierarchical tree of terminal sessions and folders.
 *
 * The tree root is hidden. Top-level children are folders (sorted by order)
 * and root-level sessions (those with folderId == null, sorted by order).
 * Sessions inside folders appear as children of the corresponding folder node.
 */
class SessionTreeView : VBox() {

    companion object {
        val SESSION_ID_FORMAT = DataFormat("application/x-crank-session-id")
    }

    // ------------------------------------------------------------------ callbacks

    var onSessionSelected: ((TerminalSession) -> Unit)? = null
    var onSessionMoved: ((sessionId: String, newFolderId: String?) -> Unit)? = null
    var onNewTerminalRequested: ((folderId: String?) -> Unit)? = null
    var onNewFolderRequested: (() -> Unit)? = null
    var onSessionRemoved: ((TerminalSession) -> Unit)? = null
    var onFolderRenamed: ((SessionFolder) -> Unit)? = null
    var onFolderRemoved: ((SessionFolder) -> Unit)? = null
    var onSessionRenamed: ((TerminalSession) -> Unit)? = null

    // ------------------------------------------------------------------ UI components

    private val treeView = TreeView<Any>()
    private val hiddenRoot = TreeItem<Any>("root")

    // ------------------------------------------------------------------ status tracking

    /** Per-session status state used to color the indicator circle. */
    private data class SessionStatus(
        val state: SshSessionWorker.State = SshSessionWorker.State.DISCONNECTED,
        val inactive: Boolean = false
    )

    private val sessionStatuses = ConcurrentHashMap<String, SessionStatus>()
    private val sessionTitles = ConcurrentHashMap<String, String>()
    private val sessionBandwidth = ConcurrentHashMap<String, Double>()
    private var maxBandwidth: Double = 0.0

    // Quick lookup: sessionId -> TreeItem so we can update indicators and select programmatically
    private val sessionTreeItems = mutableMapOf<String, TreeItem<Any>>()
    private val folderTreeItems = mutableMapOf<String, TreeItem<Any>>()

    // ------------------------------------------------------------------ focus mode

    data class BandwidthEntry(
        val sessionId: String,
        val sessionName: String,
        val state: SshSessionWorker.State,
        val bytesPerSecond: Double
    )

    private val bandwidthListView = ListView<BandwidthEntry>()
    private val bandwidthItems = FXCollections.observableArrayList<BandwidthEntry>()
    private val focusModeToggle = ToggleButton(null, CrankIcons.icon(CrankIcons.EYE)).apply {
        tooltip = Tooltip("Focus mode — bandwidth-sorted session list")
    }
    private var focusModeActive = false

    // ------------------------------------------------------------------ init

    init {
        // ---------- toolbar ----------
        val newTerminalBtn = Button(null, CrankIcons.icon(CrankIcons.TERMINAL)).apply {
            tooltip = Tooltip("New terminal session")
            setOnAction { onNewTerminalRequested?.invoke(getSelectedFolderId()) }
        }
        val newFolderBtn = Button(null, CrankIcons.icon(CrankIcons.FOLDER_PLUS)).apply {
            tooltip = Tooltip("New folder")
            setOnAction { onNewFolderRequested?.invoke() }
        }
        focusModeToggle.setOnAction { toggleFocusMode() }
        val toolbar = ToolBar(newTerminalBtn, newFolderBtn, focusModeToggle)

        // ---------- tree view ----------
        treeView.isShowRoot = false
        treeView.root = hiddenRoot
        hiddenRoot.isExpanded = true

        treeView.setCellFactory { SessionTreeCell() }

        treeView.selectionModel.selectedItemProperty().addListener { _, _, newItem ->
            val value = newItem?.value
            if (value is TerminalSession) {
                onSessionSelected?.invoke(value)
            }
        }

        // ---------- bandwidth list view ----------
        bandwidthListView.items = bandwidthItems
        bandwidthListView.setCellFactory { BandwidthCell() }
        bandwidthListView.isVisible = false
        bandwidthListView.isManaged = false
        bandwidthListView.selectionModel.selectedItemProperty().addListener { _, _, newItem ->
            if (newItem != null) {
                // Find the session and fire selection callback
                val sessionItem = sessionTreeItems[newItem.sessionId]
                val session = sessionItem?.value as? TerminalSession
                if (session != null) {
                    onSessionSelected?.invoke(session)
                }
            }
        }

        VBox.setVgrow(treeView, Priority.ALWAYS)
        VBox.setVgrow(bandwidthListView, Priority.ALWAYS)

        children.addAll(toolbar, treeView, bandwidthListView)
    }

    // ------------------------------------------------------------------ public API

    /**
     * Rebuild the entire tree from the given sessions and folders.
     * Folders are shown first (sorted by order), followed by root-level sessions (sorted by order).
     * Sessions with a folderId are nested inside their folder.
     */
    fun refresh(sessions: List<TerminalSession>, folders: List<SessionFolder>) {
        val selectedSessionId = (treeView.selectionModel.selectedItem?.value as? TerminalSession)?.id

        sessionTreeItems.clear()
        folderTreeItems.clear()
        hiddenRoot.children.clear()

        // Build folder tree items (supports one level of nesting via parentId)
        val topFolders = folders.filter { it.parentId == null }.sortedBy { it.order }
        val childFolders = folders.filter { it.parentId != null }.groupBy { it.parentId }

        for (folder in topFolders) {
            val folderItem = TreeItem<Any>(folder)
            folderItem.isExpanded = true
            folderTreeItems[folder.id] = folderItem

            // Nested sub-folders
            val subFolders = childFolders[folder.id]?.sortedBy { it.order } ?: emptyList()
            for (sub in subFolders) {
                val subItem = TreeItem<Any>(sub)
                subItem.isExpanded = true
                folderTreeItems[sub.id] = subItem
                folderItem.children.add(subItem)
            }

            hiddenRoot.children.add(folderItem)
        }

        // Place sessions into their folders or at root
        val sessionsByFolder = sessions.groupBy { it.folderId }

        // Sessions inside folders
        for ((folderId, folderSessions) in sessionsByFolder) {
            if (folderId == null) continue
            val folderItem = folderTreeItems[folderId] ?: continue
            for (session in folderSessions.sortedBy { it.order }) {
                val sessionItem = TreeItem<Any>(session)
                sessionTreeItems[session.id] = sessionItem
                folderItem.children.add(sessionItem)
            }
        }

        // Sessions inside sub-folders (already handled above since folderTreeItems includes sub-folders)

        // Root-level sessions (folderId == null)
        val rootSessions = sessionsByFolder[null]?.sortedBy { it.order } ?: emptyList()
        for (session in rootSessions) {
            val sessionItem = TreeItem<Any>(session)
            sessionTreeItems[session.id] = sessionItem
            hiddenRoot.children.add(sessionItem)
        }

        // Restore selection if possible
        if (selectedSessionId != null) {
            selectSession(selectedSessionId)
        }
    }

    /**
     * Update the status data for a specific session.
     * Does NOT trigger a cell re-render — call [refreshCells] after all updates.
     */
    fun updateSessionStatus(sessionId: String, state: SshSessionWorker.State, inactive: Boolean) {
        sessionStatuses[sessionId] = SessionStatus(state, inactive)
    }

    /**
     * Re-render all visible tree cells in a single pass (no null-swap flicker).
     * Call this once per update cycle after updating status/bandwidth/title data.
     */
    fun refreshCells() {
        Platform.runLater {
            treeView.refresh()
        }
    }

    /**
     * Programmatically select the tree item corresponding to the given session ID.
     */
    fun selectSession(sessionId: String) {
        val item = sessionTreeItems[sessionId]
        if (item != null) {
            treeView.selectionModel.select(item)
            val index = treeView.getRow(item)
            if (index >= 0) {
                treeView.scrollTo(index)
            }
        }
    }

    /**
     * Update the terminal title for a session.
     * Does NOT trigger a cell re-render — call [refreshCells] after all updates.
     */
    fun updateSessionTitle(sessionId: String, title: String) {
        sessionTitles[sessionId] = title
    }

    /**
     * Returns the folder ID of the currently selected tree item:
     * - If a folder is selected, returns that folder's ID.
     * - If a session inside a folder is selected, returns the parent folder's ID.
     * - Otherwise returns null (root-level or nothing selected).
     */
    private fun getSelectedFolderId(): String? {
        val selected = treeView.selectionModel.selectedItem ?: return null
        val value = selected.value
        if (value is SessionFolder) return value.id
        if (value is TerminalSession && value.folderId != null) return value.folderId
        return null
    }

    // ------------------------------------------------------------------ focus mode controls

    private fun toggleFocusMode() {
        focusModeActive = focusModeToggle.isSelected
        if (focusModeActive) {
            // Shrink tree, show bandwidth list prominently
            treeView.maxHeight = 200.0
            VBox.setVgrow(treeView, Priority.NEVER)
            bandwidthListView.isVisible = true
            bandwidthListView.isManaged = true
            VBox.setVgrow(bandwidthListView, Priority.ALWAYS)
        } else {
            // Restore normal layout
            treeView.maxHeight = Double.MAX_VALUE
            VBox.setVgrow(treeView, Priority.ALWAYS)
            bandwidthListView.isVisible = false
            bandwidthListView.isManaged = false
        }
    }

    /**
     * Called from MainWindow's 500ms loop with current bandwidth data per session.
     * Updates both the focus mode list (sorted ascending, idle at top by name)
     * and per-session bandwidth for the tree view meter.
     */
    fun updateBandwidthList(entries: List<BandwidthEntry>) {
        // Always update bandwidth data for tree view meters
        val newMax = entries.maxOfOrNull { it.bytesPerSecond } ?: 0.0
        maxBandwidth = newMax
        for (entry in entries) {
            sessionBandwidth[entry.sessionId] = entry.bytesPerSecond
        }

        if (focusModeActive) {
            Platform.runLater {
                val sorted = entries.sortedWith(
                    compareBy<BandwidthEntry> { it.bytesPerSecond }
                        .thenBy { it.sessionName.lowercase() }
                )
                bandwidthItems.setAll(sorted)
            }
        }
    }

    private inner class BandwidthCell : ListCell<BandwidthEntry>() {
        override fun updateItem(item: BandwidthEntry?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                text = null
                graphic = null
                return
            }

            val color = when (item.state) {
                SshSessionWorker.State.CONNECTED -> Color.LIMEGREEN
                SshSessionWorker.State.CONNECTING, SshSessionWorker.State.RECONNECTING -> Color.GOLD
                SshSessionWorker.State.DISCONNECTED -> Color.TOMATO
            }
            val circle = Circle(5.0, color)
            val nameLabel = Label(item.sessionName.ifEmpty { "Session" })
            nameLabel.style = "-fx-text-fill: #2A2A2A;"

            val rateText = formatDataRate(item.bytesPerSecond)
            val rateLabel = Label(rateText)
            rateLabel.style = "-fx-text-fill: #3A3A3A; -fx-font-size: 11;"

            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)

            graphic = HBox(6.0, circle, nameLabel, spacer, rateLabel).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(2.0, 4.0, 2.0, 4.0)
            }
        }

        private fun formatDataRate(bytesPerSec: Double): String {
            return when {
                bytesPerSec < 1.0 -> "idle"
                bytesPerSec < 1024 -> "${bytesPerSec.toInt()} B/s"
                bytesPerSec < 1024 * 1024 -> "${"%.1f".format(bytesPerSec / 1024)} KB/s"
                else -> "${"%.1f".format(bytesPerSec / (1024 * 1024))} MB/s"
            }
        }
    }

    // ------------------------------------------------------------------ tree cell

    /**
     * Custom cell that renders folders and sessions differently.
     */
    private inner class SessionTreeCell : TreeCell<Any>() {

        init {
            // Drag detected: start drag for session items
            setOnDragDetected { event ->
                val item = treeItem?.value
                if (item is TerminalSession) {
                    val db = startDragAndDrop(TransferMode.MOVE)
                    val content = ClipboardContent()
                    content[SESSION_ID_FORMAT] = item.id
                    db.setContent(content)
                    event.consume()
                }
            }

            // Drag over: accept drops on folders and root (empty cells)
            setOnDragOver { event ->
                if (event.gestureSource != this && event.dragboard.hasContent(SESSION_ID_FORMAT)) {
                    val target = treeItem?.value
                    if (target is SessionFolder || isEmpty) {
                        event.acceptTransferModes(TransferMode.MOVE)
                    }
                }
                event.consume()
            }

            // Visual feedback on drag enter/exit
            setOnDragEntered { event ->
                if (event.gestureSource != this && event.dragboard.hasContent(SESSION_ID_FORMAT)) {
                    val target = treeItem?.value
                    if (target is SessionFolder || isEmpty) {
                        style = "-fx-background-color: #3A3A5A;"
                    }
                }
                event.consume()
            }

            setOnDragExited { event ->
                style = ""
                event.consume()
            }

            // Drop: invoke callback with session ID and new folder ID
            setOnDragDropped { event ->
                val db = event.dragboard
                var success = false
                if (db.hasContent(SESSION_ID_FORMAT)) {
                    val sessionId = db.getContent(SESSION_ID_FORMAT) as String
                    val target = treeItem?.value
                    val newFolderId = when (target) {
                        is SessionFolder -> target.id
                        else -> null // dropped on empty = move to root
                    }
                    onSessionMoved?.invoke(sessionId, newFolderId)
                    success = true
                }
                event.isDropCompleted = success
                event.consume()
            }
        }

        override fun updateItem(item: Any?, empty: Boolean) {
            super.updateItem(item, empty)

            if (empty || item == null) {
                text = null
                graphic = null
                contextMenu = null
                style = ""
                return
            }

            // Zero cell padding so the graphic controls all spacing
            style = "-fx-padding: 0;"

            when (item) {
                is SessionFolder -> {
                    text = null
                    graphic = buildFolderGraphic(item)
                    contextMenu = buildFolderContextMenu(item)
                    // Vertically center the disclosure arrow with the folder content
                    disclosureNode?.style = "-fx-padding: 4 4 4 0;"
                }
                is TerminalSession -> {
                    text = null
                    graphic = buildSessionGraphic(item)
                    contextMenu = buildSessionContextMenu(item)
                }
                else -> {
                    text = item.toString()
                    graphic = null
                    contextMenu = null
                }
            }
        }
    }

    // ------------------------------------------------------------------ graphics builders

    private fun buildFolderGraphic(folder: SessionFolder): HBox {
        val folderIcon = Label("\uD83D\uDCC1") // folder emoji as fallback icon
        folderIcon.style = "-fx-font-size: 14;"
        val nameLabel = Label(folder.name)
        nameLabel.style = "-fx-text-fill: #2A2A2A; -fx-font-weight: bold;"

        // Count child sessions' connection states for status aggregation
        val folderItem = folderTreeItems[folder.id]
        val childSessions = folderItem?.children
            ?.mapNotNull { it.value as? TerminalSession }
            ?: emptyList()

        val statusCircle: Circle
        val countLabel: Label

        if (childSessions.isEmpty()) {
            statusCircle = Circle(5.0, Color.GRAY)
            countLabel = Label("(0)")
        } else {
            val connected = childSessions.count { s ->
                sessionStatuses[s.id]?.state == SshSessionWorker.State.CONNECTED
            }
            val total = childSessions.size
            val color = when {
                connected == total -> Color.LIMEGREEN
                connected > 0 -> Color.ORANGE
                else -> Color.TOMATO
            }
            statusCircle = Circle(5.0, color)
            countLabel = Label("($connected/$total)")
        }
        countLabel.style = "-fx-text-fill: #3A3A3A; -fx-font-size: 11;"

        return HBox(4.0, folderIcon, statusCircle, nameLabel, countLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 6.0, 4.0, 0.0)
        }
    }

    private fun buildSessionGraphic(session: TerminalSession): VBox {
        val status = sessionStatuses[session.id] ?: SessionStatus()
        val indicatorColor = resolveIndicatorColor(status)

        val circle = Circle(5.0, indicatorColor)
        val nameLabel = Label(session.name.ifEmpty { "Session" })
        nameLabel.style = "-fx-text-fill: #2A2A2A;"
        val idPrefix = session.id.take(8)
        val idLabel = Label(idPrefix)
        idLabel.style = "-fx-text-fill: #3A3A3A; -fx-font-size: 10;"

        // Line 1: indicator, name, id
        val line1 = HBox(6.0, circle, nameLabel, idLabel).apply {
            alignment = Pos.CENTER_LEFT
        }

        // Line 2: bandwidth meter + window title
        val meterLabel = Label(buildBandwidthMeter(session.id))
        meterLabel.style = "-fx-font-size: 10; -fx-text-fill: #2A4A6A;"

        val titleText = sessionTitles[session.id]
        val line2Children = mutableListOf<javafx.scene.Node>(meterLabel)
        if (!titleText.isNullOrBlank()) {
            line2Children.add(Label(titleText).apply {
                style = "-fx-text-fill: #808080; -fx-font-size: 11;"
                maxWidth = 200.0
            })
        }
        val line2 = HBox(6.0).apply {
            this.children.addAll(line2Children)
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 0.0, 0.0, 16.0)
        }

        return VBox(1.0, line1, line2).apply {
            padding = Insets(2.0, 6.0, 2.0, 4.0)
        }
    }

    private fun buildBandwidthMeter(sessionId: String): String {
        val rate = sessionBandwidth[sessionId] ?: 0.0
        val max = maxBandwidth
        val filled = if (max < 1.0 || rate < 1.0) {
            0
        } else {
            ((rate / max) * 5).toInt().coerceIn(1, 5)
        }
        val filledChar = "\u2588"  // full block
        val emptyChar = "\u2591"   // light shade block
        return filledChar.repeat(filled) + emptyChar.repeat(5 - filled)
    }

    private fun resolveIndicatorColor(status: SessionStatus): Color {
        if (status.inactive) return Color.GRAY
        return when (status.state) {
            SshSessionWorker.State.CONNECTED -> Color.LIMEGREEN
            SshSessionWorker.State.CONNECTING, SshSessionWorker.State.RECONNECTING -> Color.GOLD
            SshSessionWorker.State.DISCONNECTED -> Color.TOMATO
        }
    }

    // ------------------------------------------------------------------ context menus

    private fun buildSessionContextMenu(session: TerminalSession): ContextMenu {
        val renameItem = MenuItem("Rename Session")
        renameItem.setOnAction {
            val dialog = TextInputDialog(session.name.ifEmpty { "Session" })
            dialog.title = "Rename Session"
            dialog.headerText = "Enter a new name for the session:"
            dialog.contentText = "Name:"
            val result = dialog.showAndWait()
            if (result.isPresent && result.get().isNotBlank()) {
                session.name = result.get().trim()
                onSessionRenamed?.invoke(session)
                treeView.refresh()
            }
        }

        val separator = SeparatorMenuItem()

        val removeItem = MenuItem("Remove Session")
        removeItem.setOnAction {
            val alert = Alert(Alert.AlertType.CONFIRMATION)
            alert.title = "Remove Session"
            alert.headerText = "Remove session \"${session.name.ifEmpty { session.id.take(8) }}\"?"
            alert.contentText = "The SSH connection will be closed and the session removed."
            val result: Optional<ButtonType> = alert.showAndWait()
            if (result.isPresent && result.get() == ButtonType.OK) {
                onSessionRemoved?.invoke(session)
            }
        }
        return ContextMenu(renameItem, separator, removeItem)
    }

    private fun buildFolderContextMenu(folder: SessionFolder): ContextMenu {
        val renameItem = MenuItem("Rename")
        renameItem.setOnAction {
            val dialog = TextInputDialog(folder.name)
            dialog.title = "Rename Folder"
            dialog.headerText = "Enter a new name for the folder:"
            dialog.contentText = "Name:"
            val result = dialog.showAndWait()
            if (result.isPresent && result.get().isNotBlank()) {
                folder.name = result.get().trim()
                onFolderRenamed?.invoke(folder)
                treeView.refresh()
            }
        }

        val removeItem = MenuItem("Remove Folder")
        removeItem.setOnAction {
            val alert = Alert(Alert.AlertType.CONFIRMATION)
            alert.title = "Remove Folder"
            alert.headerText = "Remove folder \"${folder.name}\"?"
            alert.contentText = "Sessions inside this folder will be moved to the root level."
            val result: Optional<ButtonType> = alert.showAndWait()
            if (result.isPresent && result.get() == ButtonType.OK) {
                // Move children to root by clearing their folderId
                val folderItem = folderTreeItems[folder.id]
                if (folderItem != null) {
                    for (child in folderItem.children.toList()) {
                        val childValue = child.value
                        if (childValue is TerminalSession) {
                            childValue.folderId = null
                        }
                    }
                }
                onFolderRemoved?.invoke(folder)
            }
        }

        return ContextMenu(renameItem, removeItem)
    }
}
