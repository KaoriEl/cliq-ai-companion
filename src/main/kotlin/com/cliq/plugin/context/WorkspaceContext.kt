package com.cliq.plugin.context

/**
 * Plain data carriers describing the editor context Cliq exposes to CLI agents.
 *
 * Kept as immutable snapshots: the [OpenFilesTracker] mutates internal state and
 * publishes a fresh [WorkspaceContext] on each change so consumers never reason
 * about partially updated values.
 */
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
