package ape.crank.ui

import ape.crank.model.ConnectionConfig
import ape.crank.model.TerminalSession
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import java.util.UUID

/**
 * Dialog for creating a new terminal session.
 * Allows the user to enter a session name and select a connection.
 */
class NewTerminalDialog(
    connections: List<ConnectionConfig>
) : Dialog<TerminalSession?>() {

    private val sessionNameField = TextField()
    private val connectionCombo = ComboBox<ConnectionConfig>()

    init {
        title = "New Terminal"
        headerText = "Create a new terminal session"
        isResizable = true
        dialogPane.prefWidth = 450.0

        if (connections.isEmpty()) {
            setupEmptyState()
        } else {
            setupForm(connections)
        }
    }

    private fun setupEmptyState() {
        val infoLabel = Label("No connections available. Please create a connection first.")
        infoLabel.isWrapText = true
        infoLabel.padding = Insets(20.0)

        dialogPane.content = infoLabel
        dialogPane.buttonTypes.add(ButtonType.OK)

        setResultConverter { null }
    }

    private fun setupForm(connections: List<ConnectionConfig>) {
        // Set up connection combo box
        connectionCombo.items = FXCollections.observableArrayList(connections)
        connectionCombo.setCellFactory {
            object : ListCell<ConnectionConfig>() {
                override fun updateItem(item: ConnectionConfig?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayName()
                }
            }
        }
        connectionCombo.buttonCell = object : ListCell<ConnectionConfig>() {
            override fun updateItem(item: ConnectionConfig?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.displayName()
            }
        }
        connectionCombo.selectionModel.selectFirst()
        connectionCombo.maxWidth = Double.MAX_VALUE

        // Build form grid
        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 12.0
        grid.padding = Insets(16.0)

        val col0 = ColumnConstraints()
        col0.minWidth = 100.0
        col0.hgrow = Priority.NEVER
        val col1 = ColumnConstraints()
        col1.hgrow = Priority.ALWAYS
        grid.columnConstraints.addAll(col0, col1)

        grid.add(Label("Session Name:"), 0, 0)
        grid.add(sessionNameField, 1, 0)

        grid.add(Label("Connection:"), 0, 1)
        grid.add(connectionCombo, 1, 1)

        dialogPane.content = grid

        // OK and Cancel buttons
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        // Disable OK when session name is blank
        val okButton = dialogPane.lookupButton(ButtonType.OK)
        okButton.isDisable = true
        sessionNameField.textProperty().addListener { _, _, newValue ->
            okButton.isDisable = newValue.isNullOrBlank()
        }

        // Focus the session name field when dialog opens
        sessionNameField.promptText = "Enter session name"
        setOnShown { sessionNameField.requestFocus() }

        // Result converter
        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val selectedConnection = connectionCombo.selectionModel.selectedItem
                if (selectedConnection != null && sessionNameField.text.isNotBlank()) {
                    TerminalSession(
                        id = UUID.randomUUID().toString(),
                        name = sessionNameField.text.trim(),
                        connectionId = selectedConnection.id
                    )
                } else {
                    null
                }
            } else {
                null
            }
        }
    }
}
