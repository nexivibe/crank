package ape.crank.ui

import javafx.scene.Group
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.paint.Color

/**
 * SVG icon factory using inline path data from Heroicons v2 (MIT-licensed, 24x24 outline set).
 * Uses JavaFX [SVGPath] — no external dependencies or resource files needed.
 */
object CrankIcons {

    // Heroicons v2 outline — command-line / terminal
    const val TERMINAL = "M6.75 7.5l3 2.25-3 2.25m4.5 0h3M3.75 4.5h16.5a1.5 1.5 0 011.5 1.5v12a1.5 1.5 0 01-1.5 1.5H3.75a1.5 1.5 0 01-1.5-1.5V6a1.5 1.5 0 011.5-1.5z"

    // Heroicons v2 outline — folder-plus
    const val FOLDER_PLUS = "M12 10.5v6m3-3H9m4.06-7.19l-2.12-2.12a1.5 1.5 0 00-1.06-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z"

    // Heroicons v2 outline — eye
    const val EYE = "M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178zM15 12a3 3 0 11-6 0 3 3 0 016 0z"

    // Heroicons v2 outline — clipboard
    const val CLIPBOARD = "M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3a2.25 2.25 0 00-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9.75a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184"

    // Heroicons v2 outline — folder-open
    const val FOLDER_OPEN = "M3.75 9.776c.112-.017.227-.026.344-.026h15.812c.117 0 .232.009.344.026m-16.5 0a2.25 2.25 0 00-1.883 2.542l.857 6a2.25 2.25 0 002.227 1.932H19.05a2.25 2.25 0 002.227-1.932l.857-6a2.25 2.25 0 00-1.883-2.542m-16.5 0V6A2.25 2.25 0 016 3.75h3.879a1.5 1.5 0 011.06.44l2.122 2.12a1.5 1.5 0 001.06.44H18A2.25 2.25 0 0120.25 9v.776"

    // Heroicons v2 outline — plus-circle
    const val PLUS_CIRCLE = "M12 9v6m3-3H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z"

    // Heroicons v2 outline — trash
    const val TRASH = "M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0"

    // Heroicons v2 outline — document-text
    const val DOCUMENT_TEXT = "M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z"

    /**
     * Create an icon node from SVG path data.
     *
     * @param pathData SVG `d` attribute string (designed for 24x24 viewBox)
     * @param size     target icon size in pixels (default 16)
     * @param color    stroke color (default dark gray)
     * @return a [Group] containing the scaled [SVGPath], suitable for use as a button graphic
     */
    fun icon(pathData: String, size: Double = 16.0, color: Color = Color.web("#4A4A4A")): Group {
        val path = SVGPath().apply {
            content = pathData
            fill = Color.TRANSPARENT
            stroke = color
            strokeWidth = 1.5
            strokeLineCap = StrokeLineCap.ROUND
            strokeLineJoin = StrokeLineJoin.ROUND
        }
        val scale = size / 24.0
        return Group(path).apply {
            scaleX = scale
            scaleY = scale
        }
    }
}
