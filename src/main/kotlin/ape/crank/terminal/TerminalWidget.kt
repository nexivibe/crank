package ape.crank.terminal

import javafx.application.Platform
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontPosture
import javafx.scene.text.FontWeight
import javafx.scene.text.Text

class TerminalWidget : Region() {

    private val canvas: Canvas = Canvas()
    private var buffer: TerminalBuffer? = null
    private var parser: VT100Parser? = null

    // Font metrics
    private var normalFont: Font
    private var boldFont: Font
    private var italicFont: Font
    private var boldItalicFont: Font
    var cellWidth: Double = 0.0
        private set
    var cellHeight: Double = 0.0
        private set
    private var fontBaseline: Double = 0.0

    // Current terminal dimensions in characters
    var termCols: Int = 80
        private set
    var termRows: Int = 24
        private set

    // Callbacks
    var onInput: ((String) -> Unit)? = null
    var onResize: ((Int, Int) -> Unit)? = null

    // Activity tracking
    var lastDataTimestamp: Long = 0L
        private set
    var totalBytesReceived: Long = 0L
        private set

    // Data rate tracking: ring buffer of (timestamp, byteCount) pairs
    private val dataRateWindow: MutableList<Pair<Long, Int>> = mutableListOf()
    private val dataRateWindowMs: Long = 10_000L

    // Render coalescing
    private var lastRenderedVersion: Long = -1L
    private var renderPending: Boolean = false

    init {
        // Find a suitable monospace font
        normalFont = findMonospaceFont(14.0)
        boldFont = Font.font(normalFont.family, FontWeight.BOLD, FontPosture.REGULAR, 14.0)
        italicFont = Font.font(normalFont.family, FontWeight.NORMAL, FontPosture.ITALIC, 14.0)
        boldItalicFont = Font.font(normalFont.family, FontWeight.BOLD, FontPosture.ITALIC, 14.0)

        calculateFontMetrics()

        // Add canvas as child
        children.add(canvas)

        // Bind canvas size to region size
        canvas.widthProperty().bind(widthProperty())
        canvas.heightProperty().bind(heightProperty())

        // Listen for size changes to recalculate terminal dimensions
        widthProperty().addListener { _, _, _ -> handleLayoutResize() }
        heightProperty().addListener { _, _, _ -> handleLayoutResize() }

        // Make focusable
        isFocusTraversable = true

        // Keyboard handlers
        setOnKeyPressed { event -> handleKeyPressed(event) }
        setOnKeyTyped { event -> handleKeyTyped(event) }

        // Click to focus
        setOnMouseClicked { requestFocus() }

        // Style
        style = "-fx-background-color: black;"
    }

    private fun findMonospaceFont(size: Double): Font {
        val candidates = listOf("Consolas", "Menlo", "DejaVu Sans Mono", "Monospace", "Courier New", "Courier")
        val availableFamilies = Font.getFamilies().toSet()

        for (candidate in candidates) {
            if (availableFamilies.contains(candidate)) {
                return Font.font(candidate, size)
            }
        }

        // Fallback: use the system default monospace
        return Font.font("Monospaced", size)
    }

    private fun calculateFontMetrics() {
        val text = Text("M")
        text.font = normalFont
        val bounds = text.boundsInLocal
        cellWidth = bounds.width
        cellHeight = bounds.height
        fontBaseline = text.baselineOffset
    }

    fun attachSession(buffer: TerminalBuffer, parser: VT100Parser) {
        this.buffer = buffer
        this.parser = parser
        lastRenderedVersion = -1L
        requestRender()
    }

    fun detachSession() {
        this.buffer = null
        this.parser = null
        lastRenderedVersion = -1L
        // Clear the canvas
        val gc = canvas.graphicsContext2D
        gc.fill = Color.BLACK
        gc.fillRect(0.0, 0.0, canvas.width, canvas.height)
    }

    fun feedData(data: ByteArray) {
        val now = System.currentTimeMillis()
        lastDataTimestamp = now
        totalBytesReceived += data.size

        // Track for data rate calculation
        synchronized(dataRateWindow) {
            dataRateWindow.add(Pair(now, data.size))
            // Prune old entries
            val cutoff = now - dataRateWindowMs
            dataRateWindow.removeAll { it.first < cutoff }
        }

        parser?.feed(data)
        requestRender()
    }

    /**
     * Returns the recent data rate in bytes per second, calculated over a 10-second window.
     */
    fun getRecentDataRate(): Double {
        val now = System.currentTimeMillis()
        synchronized(dataRateWindow) {
            val cutoff = now - dataRateWindowMs
            dataRateWindow.removeAll { it.first < cutoff }

            if (dataRateWindow.isEmpty()) return 0.0

            val totalBytes = dataRateWindow.sumOf { it.second.toLong() }
            val windowStart = dataRateWindow.first().first
            val elapsed = (now - windowStart).coerceAtLeast(1L)
            return totalBytes * 1000.0 / elapsed
        }
    }

    /**
     * Returns true if no data has been received for the given threshold.
     */
    fun isInactive(thresholdMs: Long): Boolean {
        if (lastDataTimestamp == 0L) return true
        return (System.currentTimeMillis() - lastDataTimestamp) > thresholdMs
    }

    private fun requestRender() {
        if (renderPending) return
        renderPending = true
        Platform.runLater {
            renderPending = false
            render()
        }
    }

    private fun render() {
        val buf = buffer ?: return
        if (buf.version == lastRenderedVersion) return
        lastRenderedVersion = buf.version

        val gc = canvas.graphicsContext2D
        val w = canvas.width
        val h = canvas.height

        // Clear entire canvas with black
        gc.fill = Color.BLACK
        gc.fillRect(0.0, 0.0, w, h)

        // Draw each cell
        for (row in 0 until buf.rows) {
            for (col in 0 until buf.cols) {
                val x = col * cellWidth
                val y = row * cellHeight
                if (x >= w || y >= h) continue

                val cell = buf.getCell(row, col)
                val isCursor = buf.cursorVisible && row == buf.cursorRow && col == buf.cursorCol

                drawCell(gc, cell, x, y, isCursor)
            }
        }

        buf.dirty = false
    }

    private fun drawCell(gc: GraphicsContext, cell: Cell, x: Double, y: Double, isCursor: Boolean) {
        var fg = cell.attrs.fg
        var bg = cell.attrs.bg

        // Handle inverse attribute
        if (cell.attrs.inverse) {
            val tmp = fg
            fg = bg
            bg = tmp
        }

        // Handle cursor display (invert colors at cursor position)
        if (isCursor) {
            val tmp = fg
            fg = bg
            bg = tmp
        }

        // Handle dim attribute
        if (cell.attrs.dim) {
            fg = Color.color(fg.red * 0.5, fg.green * 0.5, fg.blue * 0.5)
        }

        // Handle hidden attribute
        if (cell.attrs.hidden) {
            fg = bg
        }

        // Draw background
        if (bg != Color.BLACK || isCursor) {
            gc.fill = bg
            gc.fillRect(x, y, cellWidth, cellHeight)
        }

        // Draw character
        val ch = cell.char
        if (ch != ' ' && ch.code >= 32) {
            // Select font based on bold/italic
            gc.font = when {
                cell.attrs.bold && cell.attrs.italic -> boldItalicFont
                cell.attrs.bold -> boldFont
                cell.attrs.italic -> italicFont
                else -> normalFont
            }
            gc.fill = fg
            gc.fillText(ch.toString(), x, y + fontBaseline)
        }

        // Draw underline
        if (cell.attrs.underline) {
            gc.stroke = fg
            gc.lineWidth = 1.0
            val underlineY = y + cellHeight - 1.5
            gc.strokeLine(x, underlineY, x + cellWidth, underlineY)
        }

        // Draw strikethrough
        if (cell.attrs.strikethrough) {
            gc.stroke = fg
            gc.lineWidth = 1.0
            val strikeY = y + cellHeight / 2.0
            gc.strokeLine(x, strikeY, x + cellWidth, strikeY)
        }
    }

    private fun handleLayoutResize() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || cellWidth <= 0 || cellHeight <= 0) return

        val newCols = maxOf(1, (w / cellWidth).toInt())
        val newRows = maxOf(1, (h / cellHeight).toInt())

        if (newCols != termCols || newRows != termRows) {
            termCols = newCols
            termRows = newRows
            buffer?.resize(newCols, newRows)
            onResize?.invoke(newCols, newRows)
            requestRender()
        }
    }

    private fun handleKeyPressed(event: KeyEvent) {
        val buf = buffer ?: return

        // Handle Ctrl+key combinations (Ctrl+A=0x01 .. Ctrl+Z=0x1A, etc.)
        if (event.isControlDown && !event.isAltDown && !event.isMetaDown) {
            val code = event.code
            val ctrlChar: Char? = when {
                // Ctrl+A through Ctrl+Z -> 0x01 through 0x1A
                code.isLetterKey && code.name.length == 1 -> (code.name[0].uppercaseChar() - 'A' + 1).toChar()
                // Ctrl+[ -> ESC (0x1B), Ctrl+\ -> 0x1C, Ctrl+] -> 0x1D, Ctrl+^ -> 0x1E, Ctrl+_ -> 0x1F
                code == KeyCode.OPEN_BRACKET -> '\u001B'
                code == KeyCode.BACK_SLASH -> '\u001C'
                code == KeyCode.CLOSE_BRACKET -> '\u001D'
                code == KeyCode.DIGIT6 -> '\u001E' // Ctrl+6 = Ctrl+^ on US keyboards
                code == KeyCode.MINUS -> '\u001F'
                code == KeyCode.SPACE -> '\u0000' // Ctrl+Space -> NUL
                else -> null
            }
            if (ctrlChar != null) {
                onInput?.invoke(ctrlChar.toString())
                event.consume()
                return
            }
        }

        // Handle Alt+key (send ESC prefix)
        if (event.isAltDown && !event.isControlDown && !event.isMetaDown) {
            val code = event.code
            if (code.isLetterKey && code.name.length == 1) {
                val ch = if (event.isShiftDown) code.name[0].uppercaseChar() else code.name[0].lowercaseChar()
                onInput?.invoke("\u001B$ch")
                event.consume()
                return
            }
        }

        val seq: String? = when (event.code) {
            KeyCode.ENTER -> "\r"
            KeyCode.BACK_SPACE -> "\u007F"
            KeyCode.TAB -> "\t"
            KeyCode.ESCAPE -> "\u001B"

            KeyCode.UP -> if (buf.cursorKeyMode) "\u001BOA" else "\u001B[A"
            KeyCode.DOWN -> if (buf.cursorKeyMode) "\u001BOB" else "\u001B[B"
            KeyCode.RIGHT -> if (buf.cursorKeyMode) "\u001BOC" else "\u001B[C"
            KeyCode.LEFT -> if (buf.cursorKeyMode) "\u001BOD" else "\u001B[D"

            KeyCode.HOME -> "\u001B[H"
            KeyCode.END -> "\u001B[F"
            KeyCode.PAGE_UP -> "\u001B[5~"
            KeyCode.PAGE_DOWN -> "\u001B[6~"
            KeyCode.INSERT -> "\u001B[2~"
            KeyCode.DELETE -> "\u001B[3~"

            KeyCode.F1 -> "\u001BOP"
            KeyCode.F2 -> "\u001BOQ"
            KeyCode.F3 -> "\u001BOR"
            KeyCode.F4 -> "\u001BOS"
            KeyCode.F5 -> "\u001B[15~"
            KeyCode.F6 -> "\u001B[17~"
            KeyCode.F7 -> "\u001B[18~"
            KeyCode.F8 -> "\u001B[19~"
            KeyCode.F9 -> "\u001B[20~"
            KeyCode.F10 -> "\u001B[21~"
            KeyCode.F11 -> "\u001B[23~"
            KeyCode.F12 -> "\u001B[24~"

            else -> null
        }

        if (seq != null) {
            onInput?.invoke(seq)
            event.consume()
        }
    }

    private fun handleKeyTyped(event: KeyEvent) {
        val ch = event.character
        if (ch.isEmpty()) return

        val c = ch[0]
        // Filter out control characters already handled by onKeyPressed
        if (c == '\r' || c == '\n' || c == '\t' || c == '\u001B' || c == '\u007F' || c == '\u0008') return

        // Skip control chars that were handled as Ctrl+key in handleKeyPressed
        if (c.code in 1..26 || c.code == 0) return

        if (c.code >= 32) {
            onInput?.invoke(ch)
            event.consume()
        }
    }

    override fun layoutChildren() {
        // Canvas is bound to our size, just ensure render happens
        requestRender()
    }

    override fun computePrefWidth(height: Double): Double {
        return 80 * cellWidth
    }

    override fun computePrefHeight(width: Double): Double {
        return 24 * cellHeight
    }

    override fun computeMinWidth(height: Double): Double {
        return 10 * cellWidth
    }

    override fun computeMinHeight(width: Double): Double {
        return 4 * cellHeight
    }
}
