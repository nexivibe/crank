package ape.crank.ui

import ape.crank.model.ConnectionConfig
import ape.crank.model.KnownHostsPolicy
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.stage.FileChooser
import java.util.UUID

/**
 * Dialog for managing SSH connections (create, edit, delete).
 * Displays a list of connections on the left and an edit form on the right.
 */
class ConnectionDialog(
    private val connections: MutableList<ConnectionConfig>,
    private val sessionCountsByConnection: Map<String, Int>,
    private val onConnectionRemoved: (ConnectionConfig) -> Unit
) : Dialog<List<ConnectionConfig>>() {

    private val connectionList = ListView<ConnectionConfig>()
    private val observableConnections = FXCollections.observableArrayList(connections)

    // Form fields
    private val nameField = TextField()
    private val hostField = TextField()
    private val portSpinner = Spinner<Int>(1, 65535, 22)
    private val usernameField = TextField()
    private val privateKeyPathField = TextField()
    private val browseButton = Button("Browse", CrankIcons.icon(CrankIcons.FOLDER_OPEN, size = 14.0))
    private val knownHostsPolicyCombo = ComboBox<KnownHostsPolicy>()
    private val keepAliveSpinner = Spinner<Int>(0, 3600, 0)
    private val connectionTimeoutSpinner = Spinner<Int>(0, 300, 0)
    private val compressionCheckBox = CheckBox("Enable compression")
    private val initialCommandField = TextField()
    private val labelField = TextField()
    private val colorPicker = ColorPicker()

    private val formPane = GridPane()
    private var updatingForm = false
    private var currentConnection: ConnectionConfig? = null

    init {
        title = "Manage Connections"
        isResizable = true
        dialogPane.prefWidth = 820.0
        dialogPane.prefHeight = 620.0

        setupListView()
        setupForm()
        setupLayout()
        setupResultConverter()
        bindFormListeners()

        // Select first connection if available
        if (observableConnections.isNotEmpty()) {
            connectionList.selectionModel.select(0)
        }
    }

    private fun setupListView() {
        connectionList.items = observableConnections
        connectionList.prefWidth = 220.0
        connectionList.setCellFactory {
            object : ListCell<ConnectionConfig>() {
                override fun updateItem(item: ConnectionConfig?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayName()
                }
            }
        }

        connectionList.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            if (newValue != null) {
                populateForm(newValue)
            } else {
                clearForm()
            }
        }
    }

    private fun setupForm() {
        formPane.hgap = 10.0
        formPane.vgap = 8.0
        formPane.padding = Insets(10.0)

        // Configure spinners to be editable
        portSpinner.isEditable = true
        portSpinner.prefWidth = 120.0
        keepAliveSpinner.isEditable = true
        keepAliveSpinner.prefWidth = 120.0
        connectionTimeoutSpinner.isEditable = true
        connectionTimeoutSpinner.prefWidth = 120.0

        // Commit spinner values on focus lost so typed values are captured
        commitSpinnerOnFocusLost(portSpinner)
        commitSpinnerOnFocusLost(keepAliveSpinner)
        commitSpinnerOnFocusLost(connectionTimeoutSpinner)

        // Known hosts policy combo
        knownHostsPolicyCombo.items = FXCollections.observableArrayList(*KnownHostsPolicy.entries.toTypedArray())
        knownHostsPolicyCombo.prefWidth = 200.0

        // Color picker
        colorPicker.prefWidth = 200.0

        // Private key path row with browse button
        val keyPathBox = HBox(8.0, privateKeyPathField, browseButton)
        HBox.setHgrow(privateKeyPathField, Priority.ALWAYS)

        val keyFormatLabel = Label("Supports OpenSSH, PEM, PKCS8 formats (Ed25519, ECDSA, RSA)")
        keyFormatLabel.style = "-fx-font-size: 11px; -fx-text-fill: #888888;"

        // Build the form grid
        var row = 0

        formPane.add(Label("Name:"), 0, row)
        formPane.add(nameField, 1, row)
        row++

        formPane.add(Label("Host:"), 0, row)
        formPane.add(hostField, 1, row)
        row++

        formPane.add(Label("Port:"), 0, row)
        formPane.add(portSpinner, 1, row)
        row++

        formPane.add(Label("Username:"), 0, row)
        formPane.add(usernameField, 1, row)
        row++

        formPane.add(Label("Private Key Path:"), 0, row)
        formPane.add(keyPathBox, 1, row)
        row++

        formPane.add(Label(), 0, row)
        formPane.add(keyFormatLabel, 1, row)
        row++

        formPane.add(Label("Known Hosts Policy:"), 0, row)
        formPane.add(knownHostsPolicyCombo, 1, row)
        row++

        formPane.add(Label("Keep-Alive Interval (s):"), 0, row)
        formPane.add(keepAliveSpinner, 1, row)
        row++

        val keepAliveHelpLabel = Label("0 = disabled (no keep-alive)")
        keepAliveHelpLabel.style = "-fx-font-size: 11px; -fx-text-fill: #888888;"
        formPane.add(Label(), 0, row)
        formPane.add(keepAliveHelpLabel, 1, row)
        row++

        formPane.add(Label("Connection Timeout (s):"), 0, row)
        formPane.add(connectionTimeoutSpinner, 1, row)
        row++

        val timeoutHelpLabel = Label("0 = disabled (no timeout)")
        timeoutHelpLabel.style = "-fx-font-size: 11px; -fx-text-fill: #888888;"
        formPane.add(Label(), 0, row)
        formPane.add(timeoutHelpLabel, 1, row)
        row++

        formPane.add(Label("Compression:"), 0, row)
        formPane.add(compressionCheckBox, 1, row)
        row++

        initialCommandField.promptText = "e.g. cd /app && tail -f logs/%UUID%.log"
        formPane.add(Label("Initial Command:"), 0, row)
        formPane.add(initialCommandField, 1, row)
        row++

        val initialCmdHelpLabel = Label("Variables: %UUID% = session ID, %NAME% = connection name")
        initialCmdHelpLabel.style = "-fx-font-size: 11px; -fx-text-fill: #888888;"
        formPane.add(Label(), 0, row)
        formPane.add(initialCmdHelpLabel, 1, row)
        row++

        formPane.add(Label("Label:"), 0, row)
        formPane.add(labelField, 1, row)
        row++

        formPane.add(Label("Color:"), 0, row)
        formPane.add(colorPicker, 1, row)

        // Column constraints
        val col0 = ColumnConstraints()
        col0.minWidth = 160.0
        col0.hgrow = Priority.NEVER
        val col1 = ColumnConstraints()
        col1.hgrow = Priority.ALWAYS
        formPane.columnConstraints.addAll(col0, col1)

        // Browse button action
        browseButton.setOnAction {
            val fileChooser = FileChooser()
            fileChooser.title = "Select Private Key File"
            fileChooser.extensionFilters.addAll(
                FileChooser.ExtensionFilter("All Files", "*.*"),
                FileChooser.ExtensionFilter("PEM Files", "*.pem"),
                FileChooser.ExtensionFilter("Key Files", "*.key")
            )
            val file = fileChooser.showOpenDialog(dialogPane.scene?.window)
            if (file != null) {
                privateKeyPathField.text = file.absolutePath
            }
        }
    }

    private fun <T> commitSpinnerOnFocusLost(spinner: Spinner<T>) {
        spinner.focusedProperty().addListener { _, _, focused ->
            if (!focused) {
                try {
                    spinner.increment(0)
                } catch (_: Exception) {
                    // Ignore parse errors; spinner retains previous value
                }
            }
        }
    }

    private fun setupLayout() {
        // Bottom buttons
        val addButton = Button("Add", CrankIcons.icon(CrankIcons.PLUS_CIRCLE, size = 14.0))
        addButton.setOnAction { addNewConnection() }

        val removeButton = Button("Remove", CrankIcons.icon(CrankIcons.TRASH, size = 14.0))
        removeButton.setOnAction { removeSelectedConnection() }

        val buttonBar = HBox(10.0, addButton, removeButton)
        buttonBar.alignment = Pos.CENTER_LEFT
        buttonBar.padding = Insets(8.0, 0.0, 0.0, 0.0)

        // Left pane: list + buttons underneath
        val leftPane = VBox(8.0, connectionList, buttonBar)
        VBox.setVgrow(connectionList, Priority.ALWAYS)
        leftPane.prefWidth = 230.0

        // Right pane: scrollable form
        val formScroll = ScrollPane(formPane)
        formScroll.isFitToWidth = true
        formScroll.hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER

        // Main layout
        val splitContent = HBox(12.0, leftPane, formScroll)
        HBox.setHgrow(formScroll, Priority.ALWAYS)
        splitContent.padding = Insets(12.0)

        dialogPane.content = splitContent

        // Only a Close button
        dialogPane.buttonTypes.add(ButtonType.CLOSE)

        // Intercept close to validate
        val closeButton = dialogPane.lookupButton(ButtonType.CLOSE)
        closeButton.addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            val allErrors = mutableListOf<String>()
            for (conn in observableConnections) {
                val errors = conn.validate()
                if (errors.isNotEmpty()) {
                    allErrors.add("${conn.displayName()}: ${errors.joinToString(", ")}")
                }
            }
            if (allErrors.isNotEmpty()) {
                val alert = Alert(Alert.AlertType.ERROR)
                alert.title = "Validation Errors"
                alert.headerText = "Some connections have errors:"
                alert.contentText = allErrors.joinToString("\n")
                alert.initOwner(dialogPane.scene?.window)
                alert.showAndWait()
                event.consume()
            }
        }
    }

    private fun setupResultConverter() {
        setResultConverter {
            // Always return the modified list (Close button returns it)
            connections.clear()
            connections.addAll(observableConnections)
            connections
        }
    }

    private fun bindFormListeners() {
        nameField.textProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.let { conn ->
                    conn.name = newValue ?: ""
                    refreshListCell(conn)
                }
            }
        }

        hostField.textProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.let { conn ->
                    conn.host = newValue ?: ""
                    refreshListCell(conn)
                }
            }
        }

        portSpinner.valueProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.port = newValue ?: 22
            }
        }

        usernameField.textProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.let { conn ->
                    conn.username = newValue ?: ""
                    refreshListCell(conn)
                }
            }
        }

        privateKeyPathField.textProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.privateKeyPath = newValue ?: ""
            }
        }

        knownHostsPolicyCombo.valueProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.knownHostsPolicy = newValue ?: KnownHostsPolicy.ACCEPT_NEW
            }
        }

        keepAliveSpinner.valueProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.keepAliveIntervalSeconds = newValue ?: 0
            }
        }

        connectionTimeoutSpinner.valueProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.connectionTimeoutSeconds = newValue ?: 0
            }
        }

        compressionCheckBox.selectedProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.compression = newValue ?: false
            }
        }

        initialCommandField.textProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.initialCommand = newValue?.ifBlank { null }
            }
        }

        labelField.textProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.label = newValue?.ifBlank { null }
            }
        }

        colorPicker.valueProperty().addListener { _, _, newValue ->
            if (!updatingForm) {
                currentConnection?.color = if (newValue != null) toHexString(newValue) else null
            }
        }
    }

    private fun populateForm(config: ConnectionConfig) {
        updatingForm = true
        currentConnection = config

        nameField.text = config.name
        hostField.text = config.host
        portSpinner.valueFactory.value = config.port
        usernameField.text = config.username
        privateKeyPathField.text = config.privateKeyPath
        knownHostsPolicyCombo.value = config.knownHostsPolicy
        keepAliveSpinner.valueFactory.value = config.keepAliveIntervalSeconds
        connectionTimeoutSpinner.valueFactory.value = config.connectionTimeoutSeconds
        compressionCheckBox.isSelected = config.compression
        initialCommandField.text = config.initialCommand ?: ""
        labelField.text = config.label ?: ""
        colorPicker.value = if (config.color != null) {
            try {
                Color.web(config.color!!)
            } catch (_: Exception) {
                Color.WHITE
            }
        } else {
            Color.WHITE
        }

        setFormDisabled(false)
        updatingForm = false
    }

    private fun clearForm() {
        updatingForm = true
        currentConnection = null

        nameField.text = ""
        hostField.text = ""
        portSpinner.valueFactory.value = 22
        usernameField.text = ""
        privateKeyPathField.text = ""
        knownHostsPolicyCombo.value = KnownHostsPolicy.ACCEPT_NEW
        keepAliveSpinner.valueFactory.value = 0
        connectionTimeoutSpinner.valueFactory.value = 0
        compressionCheckBox.isSelected = false
        initialCommandField.text = ""
        labelField.text = ""
        colorPicker.value = Color.WHITE

        setFormDisabled(true)
        updatingForm = false
    }

    private fun setFormDisabled(disabled: Boolean) {
        nameField.isDisable = disabled
        hostField.isDisable = disabled
        portSpinner.isDisable = disabled
        usernameField.isDisable = disabled
        privateKeyPathField.isDisable = disabled
        browseButton.isDisable = disabled
        knownHostsPolicyCombo.isDisable = disabled
        keepAliveSpinner.isDisable = disabled
        connectionTimeoutSpinner.isDisable = disabled
        compressionCheckBox.isDisable = disabled
        initialCommandField.isDisable = disabled
        labelField.isDisable = disabled
        colorPicker.isDisable = disabled
    }

    private fun addNewConnection() {
        val newConfig = ConnectionConfig(
            id = UUID.randomUUID().toString(),
            name = "New Connection"
        )
        observableConnections.add(newConfig)
        connectionList.selectionModel.select(newConfig)
        connectionList.scrollTo(newConfig)
        nameField.requestFocus()
        nameField.selectAll()
    }

    private fun removeSelectedConnection() {
        val selected = connectionList.selectionModel.selectedItem ?: return

        // Count how many open sessions use this connection
        val openCount = sessionCountsByConnection[selected.id] ?: 0

        if (openCount > 0) {
            val alert = Alert(Alert.AlertType.CONFIRMATION)
            alert.title = "Remove Connection"
            alert.headerText = "Connection in use"
            alert.contentText =
                "Removing this connection will close $openCount associated terminal${if (openCount > 1) "s" else ""}. Continue?"
            alert.initOwner(dialogPane.scene?.window)
            val result = alert.showAndWait()
            if (result.isEmpty || result.get() != ButtonType.OK) {
                return
            }
        }

        val index = observableConnections.indexOf(selected)
        observableConnections.remove(selected)
        onConnectionRemoved(selected)

        // Select nearest item
        if (observableConnections.isNotEmpty()) {
            val newIndex = index.coerceAtMost(observableConnections.size - 1)
            connectionList.selectionModel.select(newIndex)
        }
    }

    private fun refreshListCell(config: ConnectionConfig) {
        val index = observableConnections.indexOf(config)
        if (index >= 0) {
            // Force the list view to re-render the cell
            observableConnections[index] = config
            connectionList.selectionModel.select(index)
        }
    }

    private fun toHexString(color: Color): String {
        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        return String.format("#%02x%02x%02x", r, g, b)
    }
}
