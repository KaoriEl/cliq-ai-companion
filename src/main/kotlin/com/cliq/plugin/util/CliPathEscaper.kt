package com.cliq.plugin.util

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
