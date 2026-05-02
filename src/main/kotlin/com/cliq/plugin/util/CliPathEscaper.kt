package com.cliq.plugin.util

import com.intellij.openapi.util.SystemInfo

object CliPathEscaper {

    private val POSIX_NEEDS_QUOTING = charArrayOf(
        ' ', '\t', '"', '\'', '\\', '$', '`', '!', '*', '?', '[', ']', '(', ')',
        '{', '}', '<', '>', '|', '&', ';', '#', '~',
    )

    private val WINDOWS_NEEDS_QUOTING = charArrayOf(
        ' ', '\t', '"', '&', '|', '<', '>', '^', '(', ')', '%', '!',
    )

    fun escape(path: String): String =
        if (SystemInfo.isWindows) escapeWindows(path) else escapePosix(path)

    private fun escapePosix(path: String): String {
        if (path.isEmpty()) return "''"
        if (path.none { it in POSIX_NEEDS_QUOTING }) return path

        val sb = StringBuilder(path.length + 4)
        sb.append('\'')
        for (ch in path) {
            if (ch == '\'') sb.append("'\\''") else sb.append(ch)
        }
        sb.append('\'')
        return sb.toString()
    }

    private fun escapeWindows(path: String): String {
        if (path.isEmpty()) return "''"
        if (path.none { it in WINDOWS_NEEDS_QUOTING || it == '\'' }) return path

        if ('\'' !in path) return "'$path'"

        val sb = StringBuilder(path.length + 4)
        sb.append('\'')
        for (ch in path) {
            if (ch == '\'') sb.append("''") else sb.append(ch)
        }
        sb.append('\'')
        return sb.toString()
    }
}
