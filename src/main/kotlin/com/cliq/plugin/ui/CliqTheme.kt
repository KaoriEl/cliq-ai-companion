package com.cliq.plugin.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.border.Border

/**
 * Cliq-aligned palette + reusable visual primitives. All colors go through
 * JBColor so they render correctly in both light and dark IDE themes.
 */
object CliqTheme {

    val PRIMARY: JBColor = JBColor(Color(0x01, 0xB4, 0x3C), Color(0x2B, 0xD4, 0x6A))
    val PRIMARY_HOVER: JBColor = JBColor(Color(0x01, 0xA0, 0x35), Color(0x4F, 0xE0, 0x88))
    val PRIMARY_PRESSED: JBColor = JBColor(Color(0x01, 0x88, 0x2D), Color(0x21, 0xB8, 0x58))
    val ON_PRIMARY: JBColor = JBColor(Color.WHITE, Color(0x0A, 0x0A, 0x0A))

    val SURFACE: JBColor = JBColor(Color(0xF7, 0xFA, 0xF7), Color(0x2B, 0x2D, 0x30))
    val SURFACE_BORDER: JBColor = JBColor(Color(0xD9, 0xE5, 0xD9), Color(0x39, 0x3B, 0x40))

    val DROPZONE_IDLE_BG: JBColor = JBColor(Color(0xEF, 0xF8, 0xF1), Color(0x2D, 0x33, 0x2F))
    val DROPZONE_HOVER_BG: JBColor = JBColor(Color(0xDB, 0xF3, 0xE1), Color(0x35, 0x44, 0x39))
    val DROPZONE_BORDER: JBColor = JBColor(Color(0xB6, 0xDF, 0xC0), Color(0x4A, 0x6E, 0x55))
    val DROPZONE_TEXT: JBColor = JBColor(Color(0x35, 0x6B, 0x44), Color(0xB6, 0xDF, 0xC0))

    val SECONDARY_TEXT: JBColor = JBColor(Color(0x55, 0x5C, 0x66), Color(0xB4, 0xB8, 0xBF))

    fun titleFont(base: Font): Font = base.deriveFont(Font.BOLD, base.size2D + 1f)
    fun captionFont(base: Font): Font = base.deriveFont(base.size2D - 1f)

    fun dashedBorder(color: Color = DROPZONE_BORDER): Border =
        BorderFactory.createDashedBorder(color, 1.4f, 4f, 3f, true)
}
