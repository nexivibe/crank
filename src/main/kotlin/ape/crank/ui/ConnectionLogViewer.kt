package ape.crank.ui

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.geometry.Insets
import javafx.scene.chart.BarChart
import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Dialog that displays the connection.log CSV file as a searchable table
 * with a bar chart showing disconnect counts over the last 24 hours.
 */
class ConnectionLogViewer(private val logFile: Path) : Dialog<Void>() {

    data class LogEntry(
        val state: String,
        val name: String,
        val uuid: String,
        val host: String,
        val unixMs: Long,
        val iso: String,
        val error: String,
        val duration: String
    )

    init {
        title = "Connection Log"
        isResizable = true
        dialogPane.prefWidth = 900.0
        dialogPane.prefHeight = 700.0

        val entries = parseLog(logFile)
        val observableEntries = FXCollections.observableArrayList(entries)

        val chart = buildChart(entries)
        val searchField = TextField().apply {
            promptText = "Search across all columns..."
        }

        val table = buildTable(observableEntries, searchField)

        val deleteButton = Button(null, CrankIcons.icon(CrankIcons.TRASH, size = 14.0, color = javafx.scene.paint.Color.web("#E04040"))).apply {
            tooltip = Tooltip("Delete all connection logs")
            style = "-fx-background-color: transparent; -fx-border-color: #555; -fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3; -fx-cursor: hand;"
            setOnAction {
                val confirm = Alert(Alert.AlertType.CONFIRMATION).apply {
                    title = "Delete Logs"
                    headerText = "Delete all connection logs?"
                    contentText = "This will permanently remove all log entries. This action cannot be undone."
                }
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    try {
                        Files.deleteIfExists(logFile)
                    } catch (_: Exception) { }
                    observableEntries.clear()
                    chart.data.clear()
                }
            }
        }

        val toolbar = HBox(8.0, searchField, deleteButton).apply {
            HBox.setHgrow(searchField, Priority.ALWAYS)
        }

        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            children.addAll(chart, toolbar, table)
            VBox.setVgrow(table, Priority.ALWAYS)
        }

        dialogPane.content = content
        dialogPane.buttonTypes.add(ButtonType.CLOSE)
    }

    private fun parseLog(logFile: Path): List<LogEntry> {
        if (!Files.exists(logFile)) return emptyList()
        return Files.readAllLines(logFile).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(",")
            if (parts.size < 3) return@mapNotNull null
            LogEntry(
                state = parts.getOrElse(0) { "" }.trim(),
                name = parts.getOrElse(1) { "" }.trim(),
                uuid = parts.getOrElse(2) { "" }.trim(),
                host = parts.getOrElse(3) { "" }.trim(),
                unixMs = parts.getOrElse(4) { "0" }.trim().toLongOrNull() ?: 0L,
                iso = parts.getOrElse(5) { "" }.trim(),
                error = parts.getOrElse(6) { "" }.trim(),
                duration = parts.getOrElse(7) { "" }.trim()
            )
        }
    }

    private fun buildChart(entries: List<LogEntry>): BarChart<String, Number> {
        val now = Instant.now()
        val cutoff = now.minus(24, ChronoUnit.HOURS)
        val zone = ZoneId.systemDefault()
        val hourFormatter = DateTimeFormatter.ofPattern("MMM dd HH:00")

        // Filter to inactive (disconnect) entries in the last 24 hours
        val recentDisconnects = entries.filter { it.state == "inactive" && it.unixMs > cutoff.toEpochMilli() }

        // Bucket by hour
        val buckets = mutableMapOf<String, Int>()
        // Pre-fill all 24 hour slots so the chart is continuous
        for (i in 23 downTo 0) {
            val hour = LocalDateTime.ofInstant(now.minus(i.toLong(), ChronoUnit.HOURS), zone)
                .truncatedTo(ChronoUnit.HOURS)
            buckets[hour.format(hourFormatter)] = 0
        }
        for (entry in recentDisconnects) {
            val hour = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.unixMs), zone)
                .truncatedTo(ChronoUnit.HOURS)
            val label = hour.format(hourFormatter)
            buckets[label] = (buckets[label] ?: 0) + 1
        }

        val xAxis = CategoryAxis()
        xAxis.label = "Hour"
        val yAxis = NumberAxis()
        yAxis.label = "Disconnects"
        yAxis.isMinorTickVisible = false
        // Force integer ticks
        yAxis.tickUnit = 1.0

        val series = XYChart.Series<String, Number>()
        series.name = "Disconnects"
        for ((label, count) in buckets) {
            series.data.add(XYChart.Data(label, count))
        }

        return BarChart(xAxis, yAxis).apply {
            data.add(series)
            prefHeight = 200.0
            minHeight = 200.0
            maxHeight = 200.0
            isLegendVisible = false
            animated = false
        }
    }

    private fun buildTable(observableEntries: javafx.collections.ObservableList<LogEntry>, searchField: TextField): TableView<LogEntry> {
        val filteredList = FilteredList(observableEntries) { true }

        searchField.textProperty().addListener { _, _, newValue ->
            filteredList.setPredicate { entry ->
                if (newValue.isNullOrBlank()) return@setPredicate true
                val lower = newValue.lowercase()
                entry.iso.lowercase().contains(lower) ||
                    entry.state.lowercase().contains(lower) ||
                    entry.name.lowercase().contains(lower) ||
                    entry.host.lowercase().contains(lower) ||
                    entry.uuid.lowercase().contains(lower) ||
                    entry.error.lowercase().contains(lower) ||
                    entry.duration.lowercase().contains(lower)
            }
        }

        val sortedList = SortedList(filteredList)

        val table = TableView(sortedList)
        sortedList.comparatorProperty().bind(table.comparatorProperty())

        val timeCol = TableColumn<LogEntry, String>("Time").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.iso) }
            prefWidth = 200.0
        }
        val stateCol = TableColumn<LogEntry, String>("State").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.state) }
            prefWidth = 80.0
            setCellFactory {
                object : TableCell<LogEntry, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) {
                            text = null
                            style = ""
                        } else {
                            text = item
                            style = when (item) {
                                "active" -> "-fx-text-fill: #4CAF50;"
                                "inactive" -> "-fx-text-fill: #F44336;"
                                else -> ""
                            }
                        }
                    }
                }
            }
        }
        val nameCol = TableColumn<LogEntry, String>("Name").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.name) }
            prefWidth = 140.0
        }
        val hostCol = TableColumn<LogEntry, String>("Host").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.host) }
            prefWidth = 160.0
        }
        val uuidCol = TableColumn<LogEntry, String>("UUID").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.uuid) }
            prefWidth = 120.0
        }
        val durationCol = TableColumn<LogEntry, String>("Duration").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.duration) }
            prefWidth = 100.0
        }
        val errorCol = TableColumn<LogEntry, String>("Error").apply {
            cellValueFactory = javafx.util.Callback { SimpleStringProperty(it.value.error) }
            prefWidth = 180.0
        }

        table.columns.addAll(timeCol, stateCol, nameCol, hostCol, uuidCol, durationCol, errorCol)

        // Default sort: time descending (most recent first)
        timeCol.sortType = TableColumn.SortType.DESCENDING
        table.sortOrder.add(timeCol)
        table.sort()

        return table
    }
}
