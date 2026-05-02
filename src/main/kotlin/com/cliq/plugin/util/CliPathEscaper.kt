package com.cliq.plugin.util

/**
 * Escapes a path so it can be safely passed as a single token after `@` to a
 * shell-driven CLI prompt (Claude / Gemini). Handles spaces, tabs and other
 * shell-meaningful characters by prefixing them with a backslash.
 */
object CliPathEscaper {

    private val SPECIAL_CHARS = charArrayOf(
        ' ', '\t', '"', '\'', '\\', '$', '`', '!', '*', '?', '[', ']', '(', ')',
        '{', '}', '<', '>', '|', '&', ';', '#', '~',
    )

    fun escape(path: String): String {
        val sb = StringBuilder(path.length + 8)
        for (ch in path) {
            if (ch in SPECIAL_CHARS) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }
}
