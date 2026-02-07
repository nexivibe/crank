package ape.crank.ui

import ape.crank.model.SessionFolder
import ape.crank.model.TerminalSession
import ape.crank.ssh.SshSessionWorker
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
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

    // ------------------------------------------------------------------ callbacks

    var onSessionSelected: ((TerminalSession) -> Unit)? = null
    var onNewTerminalRequested: (() -> Unit)? = null
    var onNewFolderRequested: (() -> Unit)? = null
    var onSessionRemoved: ((TerminalSession) -> Unit)? = null

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

    // Quick lookup: sessionId -> TreeItem so we can update indicators and select programmatically
    private val sessionTreeItems = mutableMapOf<String, TreeItem<Any>>()
    private val folderTreeItems = mutableMapOf<String, TreeItem<Any>>()

    // ------------------------------------------------------------------ init

    init {
        // ---------- toolbar ----------
        val newTerminalBtn = Button("New Terminal").apply {
            setOnAction { onNewTerminalRequested?.invoke() }
        }
        val newFolderBtn = Button("New Folder").apply {
            setOnAction { onNewFolderRequested?.invoke() }
        }
        val toolbar = ToolBar(newTerminalBtn, newFolderBtn)

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

        VBox.setVgrow(treeView, Priority.ALWAYS)

        children.addAll(toolbar, treeView)
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
     * Update the visual status indicator for a specific session.
     */
    fun updateSessionStatus(sessionId: String, state: SshSessionWorker.State, inactive: Boolean) {
        sessionStatuses[sessionId] = SessionStatus(state, inactive)
        // Force the cell to re-render by triggering a tree refresh on the specific item
        Platform.runLater {
            val item = sessionTreeItems[sessionId]
            if (item != null) {
                // Setting the value again triggers a cell update
                val current = item.value
                item.value = null
                item.value = current
            }
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

    // ------------------------------------------------------------------ tree cell

    /**
     * Custom cell that renders folders and sessions differently.
     */
    private inner class SessionTreeCell : TreeCell<Any>() {
        override fun updateItem(item: Any?, empty: Boolean) {
            super.updateItem(item, empty)

            if (empty || item == null) {
                text = null
                graphic = null
                contextMenu = null
                return
            }

            when (item) {
                is SessionFolder -> {
                    text = null
                    graphic = buildFolderGraphic(item)
                    contextMenu = buildFolderContextMenu(item)
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
        nameLabel.style = "-fx-text-fill: #E0E0E0; -fx-font-weight: bold;"
        return HBox(4.0, folderIcon, nameLabel).apply {
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun buildSessionGraphic(session: TerminalSession): HBox {
        val status = sessionStatuses[session.id] ?: SessionStatus()
        val indicatorColor = resolveIndicatorColor(status)

        val circle = Circle(5.0, indicatorColor)
        val nameLabel = Label(session.name.ifEmpty { "Session" })
        nameLabel.style = "-fx-text-fill: #D0D0D0;"
        val idPrefix = session.id.take(8)
        val idLabel = Label(idPrefix)
        idLabel.style = "-fx-text-fill: #808080; -fx-font-size: 10;"

        return HBox(6.0, circle, nameLabel, idLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(1.0, 0.0, 1.0, 0.0)
        }
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
        return ContextMenu(removeItem)
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
                // Trigger a refresh of this item's cell
                val item = folderTreeItems[folder.id]
                if (item != null) {
                    val current = item.value
                    item.value = null
                    item.value = current
                }
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
                // Remove the folder from the tree (the parent MainWindow should also
                // remove it from state and call refresh)
                folderTreeItems.remove(folder.id)
                val parentItem = treeView.root ?: hiddenRoot
                parentItem.children.removeIf { it.value == folder }
                // Also remove from nested parents
                for ((_, fItem) in folderTreeItems) {
                    fItem.children.removeIf { it.value == folder }
                }
            }
        }

        return ContextMenu(renameItem, removeItem)
    }
}
