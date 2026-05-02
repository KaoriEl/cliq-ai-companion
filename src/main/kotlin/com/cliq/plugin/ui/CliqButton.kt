package com.cliq.plugin.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton

class CliqButton(text: String, private val variant: Variant = Variant.PRIMARY) : JButton(text) {

    enum class Variant { PRIMARY, GHOST }

    private var hovered = false
    private var pressed = false

    init {
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.BOLD)
        border = BorderFactory.createEmptyBorder(6, 14, 6, 14)
        foreground = when (variant) {
            Variant.PRIMARY -> CliqTheme.ON_PRIMARY
            Variant.GHOST -> CliqTheme.PRIMARY
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) { pressed = false; repaint() }
        })
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, maxOf(base.height, 28))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = 14
            val w = width
            val h = height

            val fill: Color = when (variant) {
                Variant.PRIMARY -> when {
                    !isEnabled -> disabledTone(CliqTheme.PRIMARY)
                    pressed -> CliqTheme.PRIMARY_PRESSED
                    hovered -> CliqTheme.PRIMARY_HOVER
                    else -> CliqTheme.PRIMARY
                }
                Variant.GHOST -> when {
                    pressed -> withAlpha(CliqTheme.PRIMARY, 60)
                    hovered -> withAlpha(CliqTheme.PRIMARY, 30)
                    else -> Color(0, 0, 0, 0)
                }
            }
            g2.color = fill
            g2.fillRoundRect(0, 0, w, h, arc, arc)

            if (variant == Variant.GHOST) {
                g2.color = CliqTheme.PRIMARY
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun disabledTone(c: Color): Color =
        Color(c.red, c.green, c.blue, 110)

    private fun withAlpha(c: Color, alpha: Int): Color =
        Color(c.red, c.green, c.blue, alpha)
}
