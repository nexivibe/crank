package ape.crank.terminal

import javafx.scene.paint.Color

data class CellAttributes(
    val fg: Color = Color.web("#C0C0C0"),
    val bg: Color = Color.BLACK,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strikethrough: Boolean = false
)

data class Cell(
    val char: Char = ' ',
    val attrs: CellAttributes = CellAttributes()
)

class TerminalBuffer(var cols: Int = 80, var rows: Int = 24) {

    companion object {
        private const val MAX_SCROLLBACK = 10000
    }

    // Main screen buffer
    private var mainBuffer: Array<Array<Cell>> = createGrid(cols, rows)

    // Alternate screen buffer
    private var altBuffer: Array<Array<Cell>> = createGrid(cols, rows)

    // Which buffer is active
    private var usingAltScreen: Boolean = false

    // The active buffer reference
    var buffer: Array<Array<Cell>> = mainBuffer
        private set

    // Scrollback buffer (only for main screen)
    val scrollback: MutableList<Array<Cell>> = mutableListOf()

    // Cursor position
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var cursorVisible: Boolean = true

    // Saved cursor state
    private var savedCursorRow: Int = 0
    private var savedCursorCol: Int = 0
    private var savedAttrs: CellAttributes = CellAttributes()

    // Saved cursor for alternate screen
    private var altSavedCursorRow: Int = 0
    private var altSavedCursorCol: Int = 0
    private var altSavedAttrs: CellAttributes = CellAttributes()

    // Scroll region (0-based, inclusive)
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    // Current attributes applied to new characters
    var currentAttrs: CellAttributes = CellAttributes()

    // Terminal modes
    var autoWrap: Boolean = true
    var originMode: Boolean = false
    var insertMode: Boolean = false
    var bracketedPasteMode: Boolean = false
    var cursorKeyMode: Boolean = false

    // Change tracking
    var dirty: Boolean = true
    var version: Long = 0L
        private set

    // Pending wrap flag: cursor is at the right margin and next char should wrap
    private var pendingWrap: Boolean = false

    private fun markDirty() {
        dirty = true
        version++
    }

    private fun createGrid(c: Int, r: Int): Array<Array<Cell>> {
        return Array(r) { Array(c) { Cell() } }
    }

    private fun createRow(c: Int): Array<Cell> {
        return Array(c) { Cell() }
    }

    fun getCell(row: Int, col: Int): Cell {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return Cell()
        return buffer[row][col]
    }

    fun writeChar(ch: Char) {
        if (ch.code < 32) return // Control characters not handled here

        if (pendingWrap && autoWrap) {
            pendingWrap = false
            cursorCol = 0
            lineFeed()
        }

        if (insertMode) {
            // Shift characters to the right
            val row = buffer[cursorRow]
            for (i in cols - 1 downTo cursorCol + 1) {
                row[i] = row[i - 1]
            }
        }

        if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
            buffer[cursorRow][cursorCol] = Cell(ch, currentAttrs)
        }

        if (cursorCol < cols - 1) {
            cursorCol++
        } else {
            // At right margin
            if (autoWrap) {
                pendingWrap = true
            }
            // If no autowrap, cursor stays at last column
        }

        markDirty()
    }

    fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
        markDirty()
    }

    fun carriageReturn() {
        pendingWrap = false
        cursorCol = 0
        markDirty()
    }

    fun backspace() {
        pendingWrap = false
        if (cursorCol > 0) {
            cursorCol--
        }
        markDirty()
    }

    fun tab() {
        pendingWrap = false
        // Move to next tab stop (every 8 columns)
        val nextTab = ((cursorCol / 8) + 1) * 8
        cursorCol = minOf(nextTab, cols - 1)
        markDirty()
    }

    fun setCursorPosition(row: Int, col: Int) {
        pendingWrap = false
        val effectiveRow: Int
        val effectiveCol: Int

        if (originMode) {
            effectiveRow = (row + scrollTop).coerceIn(scrollTop, scrollBottom)
            effectiveCol = col.coerceIn(0, cols - 1)
        } else {
            effectiveRow = row.coerceIn(0, rows - 1)
            effectiveCol = col.coerceIn(0, cols - 1)
        }

        cursorRow = effectiveRow
        cursorCol = effectiveCol
        markDirty()
    }

    fun moveCursorUp(n: Int) {
        pendingWrap = false
        cursorRow = maxOf(cursorRow - n, scrollTop)
        markDirty()
    }

    fun moveCursorDown(n: Int) {
        pendingWrap = false
        cursorRow = minOf(cursorRow + n, scrollBottom)
        markDirty()
    }

    fun moveCursorForward(n: Int) {
        pendingWrap = false
        cursorCol = minOf(cursorCol + n, cols - 1)
        markDirty()
    }

    fun moveCursorBackward(n: Int) {
        pendingWrap = false
        cursorCol = maxOf(cursorCol - n, 0)
        markDirty()
    }

    fun scrollUp(n: Int) {
        for (i in 0 until n) {
            // Add top line to scrollback if on main screen
            if (!usingAltScreen) {
                scrollback.add(buffer[scrollTop].copyOf())
                if (scrollback.size > MAX_SCROLLBACK) {
                    scrollback.removeAt(0)
                }
            }
            // Shift lines up within scroll region
            for (row in scrollTop until scrollBottom) {
                buffer[row] = buffer[row + 1]
            }
            buffer[scrollBottom] = createRow(cols)
        }
        markDirty()
    }

    fun scrollDown(n: Int) {
        for (i in 0 until n) {
            // Shift lines down within scroll region
            for (row in scrollBottom downTo scrollTop + 1) {
                buffer[row] = buffer[row - 1]
            }
            buffer[scrollTop] = createRow(cols)
        }
        markDirty()
    }

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                // Erase from cursor to end of display
                // Clear rest of current line
                for (col in cursorCol until cols) {
                    buffer[cursorRow][col] = Cell(attrs = currentAttrs)
                }
                // Clear all lines below
                for (row in cursorRow + 1 until rows) {
                    for (col in 0 until cols) {
                        buffer[row][col] = Cell(attrs = currentAttrs)
                    }
                }
            }
            1 -> {
                // Erase from start of display to cursor
                for (row in 0 until cursorRow) {
                    for (col in 0 until cols) {
                        buffer[row][col] = Cell(attrs = currentAttrs)
                    }
                }
                // Clear current line up to and including cursor
                for (col in 0..cursorCol.coerceAtMost(cols - 1)) {
                    buffer[cursorRow][col] = Cell(attrs = currentAttrs)
                }
            }
            2 -> {
                // Erase entire display
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        buffer[row][col] = Cell(attrs = currentAttrs)
                    }
                }
            }
            3 -> {
                // Erase entire display and scrollback
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        buffer[row][col] = Cell(attrs = currentAttrs)
                    }
                }
                scrollback.clear()
            }
        }
        markDirty()
    }

    fun eraseLine(mode: Int) {
        when (mode) {
            0 -> {
                // Erase from cursor to end of line
                for (col in cursorCol until cols) {
                    buffer[cursorRow][col] = Cell(attrs = currentAttrs)
                }
            }
            1 -> {
                // Erase from start of line to cursor
                for (col in 0..cursorCol.coerceAtMost(cols - 1)) {
                    buffer[cursorRow][col] = Cell(attrs = currentAttrs)
                }
            }
            2 -> {
                // Erase entire line
                for (col in 0 until cols) {
                    buffer[cursorRow][col] = Cell(attrs = currentAttrs)
                }
            }
        }
        markDirty()
    }

    fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        for (i in 0 until count) {
            for (row in scrollBottom downTo cursorRow + 1) {
                buffer[row] = buffer[row - 1]
            }
            buffer[cursorRow] = createRow(cols)
        }
        markDirty()
    }

    fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        for (i in 0 until count) {
            for (row in cursorRow until scrollBottom) {
                buffer[row] = buffer[row + 1]
            }
            buffer[scrollBottom] = createRow(cols)
        }
        markDirty()
    }

    fun deleteCharacters(n: Int) {
        val count = n.coerceAtMost(cols - cursorCol)
        val row = buffer[cursorRow]
        for (col in cursorCol until cols - count) {
            row[col] = row[col + count]
        }
        for (col in cols - count until cols) {
            row[col] = Cell(attrs = currentAttrs)
        }
        markDirty()
    }

    fun insertCharacters(n: Int) {
        val count = n.coerceAtMost(cols - cursorCol)
        val row = buffer[cursorRow]
        for (col in cols - 1 downTo cursorCol + count) {
            row[col] = row[col - count]
        }
        for (col in cursorCol until (cursorCol + count).coerceAtMost(cols)) {
            row[col] = Cell(attrs = currentAttrs)
        }
        markDirty()
    }

    fun eraseCharacters(n: Int) {
        val count = n.coerceAtMost(cols - cursorCol)
        for (col in cursorCol until cursorCol + count) {
            buffer[cursorRow][col] = Cell(attrs = currentAttrs)
        }
        markDirty()
    }

    fun saveCursor() {
        if (usingAltScreen) {
            altSavedCursorRow = cursorRow
            altSavedCursorCol = cursorCol
            altSavedAttrs = currentAttrs
        } else {
            savedCursorRow = cursorRow
            savedCursorCol = cursorCol
            savedAttrs = currentAttrs
        }
    }

    fun restoreCursor() {
        if (usingAltScreen) {
            cursorRow = altSavedCursorRow.coerceIn(0, rows - 1)
            cursorCol = altSavedCursorCol.coerceIn(0, cols - 1)
            currentAttrs = altSavedAttrs
        } else {
            cursorRow = savedCursorRow.coerceIn(0, rows - 1)
            cursorCol = savedCursorCol.coerceIn(0, cols - 1)
            currentAttrs = savedAttrs
        }
        pendingWrap = false
        markDirty()
    }

    fun enableAlternateScreen() {
        if (usingAltScreen) return
        usingAltScreen = true
        altBuffer = createGrid(cols, rows)
        buffer = altBuffer
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
        markDirty()
    }

    fun disableAlternateScreen() {
        if (!usingAltScreen) return
        usingAltScreen = false
        buffer = mainBuffer
        pendingWrap = false
        markDirty()
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return

        val newMain = createGrid(newCols, newRows)
        val newAlt = createGrid(newCols, newRows)

        // Copy what fits from main buffer
        val copyRowsMain = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRowsMain) {
            for (c in 0 until copyCols) {
                newMain[r][c] = mainBuffer[r][c]
            }
        }

        // Copy what fits from alt buffer
        if (usingAltScreen) {
            for (r in 0 until copyRowsMain) {
                for (c in 0 until copyCols) {
                    newAlt[r][c] = altBuffer[r][c]
                }
            }
        }

        mainBuffer = newMain
        altBuffer = newAlt
        buffer = if (usingAltScreen) altBuffer else mainBuffer

        cols = newCols
        rows = newRows

        // Adjust cursor
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)

        // Reset scroll region
        scrollTop = 0
        scrollBottom = rows - 1

        pendingWrap = false
        markDirty()
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        val effectiveTop = top.coerceIn(0, rows - 1)
        val effectiveBottom = bottom.coerceIn(0, rows - 1)
        if (effectiveTop < effectiveBottom) {
            scrollTop = effectiveTop
            scrollBottom = effectiveBottom
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        // CUP to home after setting scroll region
        setCursorPosition(0, 0)
    }

    fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
        markDirty()
    }

    fun reset() {
        mainBuffer = createGrid(cols, rows)
        altBuffer = createGrid(cols, rows)
        usingAltScreen = false
        buffer = mainBuffer
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
        savedCursorRow = 0
        savedCursorCol = 0
        savedAttrs = CellAttributes()
        altSavedCursorRow = 0
        altSavedCursorCol = 0
        altSavedAttrs = CellAttributes()
        scrollTop = 0
        scrollBottom = rows - 1
        currentAttrs = CellAttributes()
        autoWrap = true
        originMode = false
        insertMode = false
        bracketedPasteMode = false
        cursorKeyMode = false
        pendingWrap = false
        markDirty()
    }
}
