package com.cliq.plugin.context

data class CursorPosition(val line: Int, val column: Int)

data class TrackedFile(
    val path: String,
    val openedAt: Long,
    val isActive: Boolean,
    val cursor: CursorPosition? = null,
    val selectedText: String? = null,
)

data class WorkspaceContext(
    val openFiles: List<TrackedFile>,
    val isTrusted: Boolean,
)
