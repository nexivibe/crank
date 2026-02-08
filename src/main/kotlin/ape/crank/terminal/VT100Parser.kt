package ape.crank.terminal

import javafx.scene.paint.Color

class VT100Parser(private val buffer: TerminalBuffer) {

    enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        OSC_STRING,
        DCS_PASSTHROUGH
    }

    // Callbacks
    var onTitleChanged: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onDeviceStatusResponse: ((String) -> Unit)? = null

    private var state: State = State.GROUND

    // CSI parameter accumulation
    private val params: MutableList<Int> = mutableListOf()
    private var currentParam: StringBuilder = StringBuilder()
    private var privateMarker: Char = '\u0000' // '?' for DEC private modes, '>' etc.
    private var intermediateChars: StringBuilder = StringBuilder()

    // OSC string accumulation
    private val oscString: StringBuilder = StringBuilder()

    // Standard colors (indices 0-7)
    private val standardColors: Array<Color> = arrayOf(
        Color.web("#000000"), // Black
        Color.web("#CC0000"), // Red
        Color.web("#00CC00"), // Green
        Color.web("#CCCC00"), // Yellow
        Color.web("#0000CC"), // Blue
        Color.web("#CC00CC"), // Magenta
        Color.web("#00CCCC"), // Cyan
        Color.web("#C0C0C0")  // White
    )

    // Bright colors (indices 8-15)
    private val brightColors: Array<Color> = arrayOf(
        Color.web("#808080"), // Gray (bright black)
        Color.web("#FF0000"), // Bright Red
        Color.web("#00FF00"), // Bright Green
        Color.web("#FFFF00"), // Bright Yellow
        Color.web("#0000FF"), // Bright Blue
        Color.web("#FF00FF"), // Bright Magenta
        Color.web("#00FFFF"), // Bright Cyan
        Color.web("#FFFFFF")  // Bright White
    )

    /**
     * Reset the parser state machine to ground state, clearing any
     * accumulated CSI/OSC parameters. Call on SSH reconnection to
     * prevent stale partial escape sequences from corrupting output.
     */
    fun reset() {
        state = State.GROUND
        params.clear()
        currentParam.clear()
        privateMarker = '\u0000'
        intermediateChars.clear()
        oscString.clear()
    }

    fun feed(data: String) {
        for (ch in data) {
            processByte(ch)
        }
    }

    fun feed(data: ByteArray) {
        val str = String(data, Charsets.UTF_8)
        feed(str)
    }

    private fun processByte(ch: Char) {
        when (state) {
            State.GROUND -> handleGround(ch)
            State.ESCAPE -> handleEscape(ch)
            State.ESCAPE_INTERMEDIATE -> handleEscapeIntermediate(ch)
            State.CSI_ENTRY -> handleCsiEntry(ch)
            State.CSI_PARAM -> handleCsiParam(ch)
            State.CSI_INTERMEDIATE -> handleCsiIntermediate(ch)
            State.CSI_IGNORE -> handleCsiIgnore(ch)
            State.OSC_STRING -> handleOscString(ch)
            State.DCS_PASSTHROUGH -> handleDcsPassthrough(ch)
        }
    }

    private fun handleGround(ch: Char) {
        when (ch) {
            '\u0000' -> { /* NUL - ignore */ }
            '\u0007' -> { onBell?.invoke() } // BEL
            '\u0008' -> { buffer.backspace() } // BS
            '\u0009' -> { buffer.tab() } // HT
            '\n', '\u000B', '\u000C' -> { buffer.lineFeed() } // LF, VT, FF
            '\r' -> { buffer.carriageReturn() } // CR
            '\u001B' -> { // ESC
                state = State.ESCAPE
            }
            else -> {
                if (ch.code >= 32) {
                    buffer.writeChar(ch)
                }
            }
        }
    }

    private fun handleEscape(ch: Char) {
        when (ch) {
            '[' -> {
                // Enter CSI
                state = State.CSI_ENTRY
                params.clear()
                currentParam.clear()
                privateMarker = '\u0000'
                intermediateChars.clear()
            }
            ']' -> {
                // Enter OSC
                state = State.OSC_STRING
                oscString.clear()
            }
            'P' -> {
                // Enter DCS
                state = State.DCS_PASSTHROUGH
            }
            '7' -> {
                // DECSC - Save cursor
                buffer.saveCursor()
                state = State.GROUND
            }
            '8' -> {
                // DECRC - Restore cursor
                buffer.restoreCursor()
                state = State.GROUND
            }
            'D' -> {
                // IND - Line feed
                buffer.lineFeed()
                state = State.GROUND
            }
            'E' -> {
                // NEL - Next line
                buffer.carriageReturn()
                buffer.lineFeed()
                state = State.GROUND
            }
            'M' -> {
                // RI - Reverse index
                buffer.reverseIndex()
                state = State.GROUND
            }
            'c' -> {
                // RIS - Full reset
                buffer.reset()
                state = State.GROUND
            }
            '=' -> {
                // DECKPAM - Keypad application mode
                state = State.GROUND
            }
            '>' -> {
                // DECKPNM - Keypad numeric mode
                state = State.GROUND
            }
            '(' , ')' , '*' , '+' -> {
                // Character set designation - consume next char
                state = State.ESCAPE_INTERMEDIATE
                intermediateChars.clear()
                intermediateChars.append(ch)
            }
            '#' -> {
                state = State.ESCAPE_INTERMEDIATE
                intermediateChars.clear()
                intermediateChars.append(ch)
            }
            else -> {
                // Unknown escape, go back to ground
                state = State.GROUND
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleEscapeIntermediate(ch: Char) {
        // Consume the final byte of a multi-byte escape sequence (e.g., ESC ( B)
        // We just ignore the character set designations for now
        state = State.GROUND
    }

    private fun handleCsiEntry(ch: Char) {
        when {
            ch == '?' || ch == '>' || ch == '!' -> {
                privateMarker = ch
                state = State.CSI_PARAM
            }
            ch in '0'..'9' -> {
                currentParam.append(ch)
                state = State.CSI_PARAM
            }
            ch == ';' -> {
                params.add(if (currentParam.isEmpty()) 0 else currentParam.toString().toIntOrNull() ?: 0)
                currentParam.clear()
                state = State.CSI_PARAM
            }
            ch in ' '..'/' -> {
                intermediateChars.append(ch)
                state = State.CSI_INTERMEDIATE
            }
            ch in '@'..'~' -> {
                // Final byte with no params
                finalizeParams()
                dispatchCsi(ch)
                state = State.GROUND
            }
            else -> {
                state = State.CSI_IGNORE
            }
        }
    }

    private fun handleCsiParam(ch: Char) {
        when {
            ch in '0'..'9' -> {
                currentParam.append(ch)
            }
            ch == ';' -> {
                params.add(if (currentParam.isEmpty()) 0 else currentParam.toString().toIntOrNull() ?: 0)
                currentParam.clear()
            }
            ch in ' '..'/' -> {
                finalizeParams()
                intermediateChars.append(ch)
                state = State.CSI_INTERMEDIATE
            }
            ch in '@'..'~' -> {
                finalizeParams()
                dispatchCsi(ch)
                state = State.GROUND
            }
            ch == '\u001B' -> {
                // ESC interrupts CSI
                state = State.ESCAPE
            }
            else -> {
                // Handle C0 controls within CSI
                handleC0InSequence(ch)
            }
        }
    }

    private fun handleCsiIntermediate(ch: Char) {
        when {
            ch in ' '..'/' -> {
                intermediateChars.append(ch)
            }
            ch in '@'..'~' -> {
                dispatchCsi(ch)
                state = State.GROUND
            }
            else -> {
                state = State.CSI_IGNORE
            }
        }
    }

    private fun handleCsiIgnore(ch: Char) {
        if (ch in '@'..'~') {
            state = State.GROUND
        }
    }

    private fun handleOscString(ch: Char) {
        when (ch) {
            '\u0007' -> {
                // BEL terminates OSC
                processOsc()
                state = State.GROUND
            }
            '\u001B' -> {
                // Could be ESC \ (ST)
                // We'll check for ST in ground mode
                // For simplicity, treat ESC as terminator if followed by \
                processOsc()
                state = State.ESCAPE
            }
            '\u009C' -> {
                // ST (8-bit)
                processOsc()
                state = State.GROUND
            }
            else -> {
                oscString.append(ch)
            }
        }
    }

    private fun handleDcsPassthrough(ch: Char) {
        when (ch) {
            '\u001B' -> {
                // Likely ESC \ (ST) - leave DCS
                state = State.ESCAPE
            }
            '\u009C' -> {
                // ST - leave DCS
                state = State.GROUND
            }
            else -> {
                // Ignore DCS content for now
            }
        }
    }

    private fun handleC0InSequence(ch: Char) {
        // Handle C0 controls embedded in sequences
        when (ch) {
            '\u0000' -> { /* NUL - ignore */ }
            '\u0007' -> { onBell?.invoke() }
            '\u0008' -> { buffer.backspace() }
            '\u0009' -> { buffer.tab() }
            '\n', '\u000B', '\u000C' -> { buffer.lineFeed() }
            '\r' -> { buffer.carriageReturn() }
            else -> { /* ignore */ }
        }
    }

    private fun finalizeParams() {
        params.add(if (currentParam.isEmpty()) 0 else currentParam.toString().toIntOrNull() ?: 0)
        currentParam.clear()
    }

    private fun getParam(index: Int, default: Int): Int {
        val value = params.getOrNull(index) ?: 0
        return if (value == 0) default else value
    }

    private fun dispatchCsi(finalByte: Char) {
        if (privateMarker == '?') {
            dispatchDecPrivateMode(finalByte)
            return
        }

        when (finalByte) {
            '@' -> { // ICH - Insert Characters
                buffer.insertCharacters(getParam(0, 1))
            }
            'A' -> { // CUU - Cursor Up
                buffer.moveCursorUp(getParam(0, 1))
            }
            'B' -> { // CUD - Cursor Down
                buffer.moveCursorDown(getParam(0, 1))
            }
            'C' -> { // CUF - Cursor Forward
                buffer.moveCursorForward(getParam(0, 1))
            }
            'D' -> { // CUB - Cursor Backward
                buffer.moveCursorBackward(getParam(0, 1))
            }
            'E' -> { // CNL - Cursor Next Line
                buffer.moveCursorDown(getParam(0, 1))
                buffer.carriageReturn()
            }
            'F' -> { // CPL - Cursor Previous Line
                buffer.moveCursorUp(getParam(0, 1))
                buffer.carriageReturn()
            }
            'G' -> { // CHA - Cursor Character Absolute
                buffer.setCursorPosition(buffer.cursorRow, getParam(0, 1) - 1)
            }
            'H', 'f' -> { // CUP/HVP - Cursor Position
                val row = getParam(0, 1) - 1
                val col = if (params.size >= 2) getParam(1, 1) - 1 else 0
                buffer.setCursorPosition(row, col)
            }
            'J' -> { // ED - Erase in Display
                buffer.eraseInDisplay(getParam(0, 0))
            }
            'K' -> { // EL - Erase in Line
                buffer.eraseLine(getParam(0, 0))
            }
            'L' -> { // IL - Insert Lines
                buffer.insertLines(getParam(0, 1))
            }
            'M' -> { // DL - Delete Lines
                buffer.deleteLines(getParam(0, 1))
            }
            'P' -> { // DCH - Delete Characters
                buffer.deleteCharacters(getParam(0, 1))
            }
            'S' -> { // SU - Scroll Up
                buffer.scrollUp(getParam(0, 1))
            }
            'T' -> { // SD - Scroll Down
                buffer.scrollDown(getParam(0, 1))
            }
            'X' -> { // ECH - Erase Characters
                buffer.eraseCharacters(getParam(0, 1))
            }
            'd' -> { // VPA - Vertical Position Absolute
                buffer.setCursorPosition(getParam(0, 1) - 1, buffer.cursorCol)
            }
            'm' -> { // SGR - Select Graphic Rendition
                handleSgr()
            }
            'n' -> { // DSR - Device Status Report
                handleDsr()
            }
            'r' -> { // DECSTBM - Set Top and Bottom Margins
                val top = getParam(0, 1) - 1
                val bottom = if (params.size >= 2) getParam(1, buffer.rows) - 1 else buffer.rows - 1
                buffer.setScrollRegion(top, bottom)
            }
            's' -> { // SCP - Save Cursor Position
                buffer.saveCursor()
            }
            'u' -> { // RCP - Restore Cursor Position
                buffer.restoreCursor()
            }
            'h' -> { // SM - Set Mode
                handleSetMode(true)
            }
            'l' -> { // RM - Reset Mode
                handleSetMode(false)
            }
        }
    }

    private fun dispatchDecPrivateMode(finalByte: Char) {
        when (finalByte) {
            'h' -> { // DECSET
                for (p in params) {
                    setDecMode(p, true)
                }
            }
            'l' -> { // DECRST
                for (p in params) {
                    setDecMode(p, false)
                }
            }
        }
    }

    private fun setDecMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> { // DECCKM - Cursor Key Mode
                buffer.cursorKeyMode = enabled
            }
            7 -> { // DECAWM - Auto Wrap Mode
                buffer.autoWrap = enabled
            }
            25 -> { // DECTCEM - Text Cursor Enable Mode
                buffer.cursorVisible = enabled
            }
            47, 1047 -> { // Alternate Screen Buffer
                if (enabled) {
                    buffer.enableAlternateScreen()
                } else {
                    buffer.disableAlternateScreen()
                }
            }
            1048 -> { // Save/Restore Cursor (as in DECSC/DECRC)
                if (enabled) {
                    buffer.saveCursor()
                } else {
                    buffer.restoreCursor()
                }
            }
            1049 -> { // Alternate Screen + Save/Restore Cursor
                if (enabled) {
                    buffer.saveCursor()
                    buffer.enableAlternateScreen()
                    buffer.eraseInDisplay(2)
                } else {
                    buffer.disableAlternateScreen()
                    buffer.restoreCursor()
                }
            }
            2004 -> { // Bracketed Paste Mode
                buffer.bracketedPasteMode = enabled
            }
        }
    }

    private fun handleSetMode(enabled: Boolean) {
        for (p in params) {
            when (p) {
                4 -> { // IRM - Insert Mode
                    buffer.insertMode = enabled
                }
            }
        }
    }

    private fun handleSgr() {
        if (params.isEmpty() || (params.size == 1 && params[0] == 0)) {
            buffer.currentAttrs = CellAttributes()
            return
        }

        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> {
                    buffer.currentAttrs = CellAttributes()
                }
                1 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(bold = true)
                }
                2 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(dim = true)
                }
                3 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(italic = true)
                }
                4 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(underline = true)
                }
                5 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(blink = true)
                }
                7 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(inverse = true)
                }
                8 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(hidden = true)
                }
                9 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(strikethrough = true)
                }
                21, 22 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(bold = false, dim = false)
                }
                23 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(italic = false)
                }
                24 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(underline = false)
                }
                25 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(blink = false)
                }
                27 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(inverse = false)
                }
                28 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(hidden = false)
                }
                29 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(strikethrough = false)
                }
                in 30..37 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(fg = standardColors[code - 30])
                }
                38 -> {
                    // Extended foreground color
                    val result = parseExtendedColor(i)
                    if (result != null) {
                        buffer.currentAttrs = buffer.currentAttrs.copy(fg = result.first)
                        i = result.second
                    }
                }
                39 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(fg = Color.web("#C0C0C0"))
                }
                in 40..47 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(bg = standardColors[code - 40])
                }
                48 -> {
                    // Extended background color
                    val result = parseExtendedColor(i)
                    if (result != null) {
                        buffer.currentAttrs = buffer.currentAttrs.copy(bg = result.first)
                        i = result.second
                    }
                }
                49 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(bg = Color.BLACK)
                }
                in 90..97 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(fg = brightColors[code - 90])
                }
                in 100..107 -> {
                    buffer.currentAttrs = buffer.currentAttrs.copy(bg = brightColors[code - 100])
                }
            }
            i++
        }
    }

    /**
     * Parse 256-color or truecolor specification starting at index i (which points to 38 or 48).
     * Returns a pair of (Color, lastConsumedIndex) or null if parsing fails.
     */
    private fun parseExtendedColor(i: Int): Pair<Color, Int>? {
        val mode = params.getOrNull(i + 1) ?: return null
        when (mode) {
            5 -> {
                // 256-color: 38;5;n or 48;5;n
                val n = params.getOrNull(i + 2) ?: return null
                val color = get256Color(n)
                return Pair(color, i + 2)
            }
            2 -> {
                // Truecolor: 38;2;r;g;b or 48;2;r;g;b
                val r = params.getOrNull(i + 2) ?: return null
                val g = params.getOrNull(i + 3) ?: return null
                val b = params.getOrNull(i + 4) ?: return null
                val color = Color.rgb(
                    r.coerceIn(0, 255),
                    g.coerceIn(0, 255),
                    b.coerceIn(0, 255)
                )
                return Pair(color, i + 4)
            }
            else -> return null
        }
    }

    private fun get256Color(n: Int): Color {
        return when {
            n in 0..7 -> standardColors[n]
            n in 8..15 -> brightColors[n - 8]
            n in 16..231 -> {
                // 6x6x6 color cube
                val index = n - 16
                val r = (index / 36) * 51
                val g = ((index % 36) / 6) * 51
                val b = (index % 6) * 51
                Color.rgb(r, g, b)
            }
            n in 232..255 -> {
                // Grayscale ramp
                val gray = 8 + (n - 232) * 10
                Color.rgb(gray, gray, gray)
            }
            else -> Color.web("#C0C0C0")
        }
    }

    private fun handleDsr() {
        when (getParam(0, 0)) {
            5 -> {
                // Status report - report OK
                onDeviceStatusResponse?.invoke("\u001B[0n")
            }
            6 -> {
                // Cursor position report
                val row = buffer.cursorRow + 1
                val col = buffer.cursorCol + 1
                onDeviceStatusResponse?.invoke("\u001B[${row};${col}R")
            }
        }
    }

    private fun processOsc() {
        val str = oscString.toString()
        val semicolonIndex = str.indexOf(';')
        if (semicolonIndex >= 0) {
            val command = str.substring(0, semicolonIndex).toIntOrNull()
            val argument = str.substring(semicolonIndex + 1)
            when (command) {
                0 -> {
                    // Set icon name and window title
                    onTitleChanged?.invoke(argument)
                }
                1 -> {
                    // Set icon name
                    onTitleChanged?.invoke(argument)
                }
                2 -> {
                    // Set window title
                    onTitleChanged?.invoke(argument)
                }
            }
        }
    }
}
