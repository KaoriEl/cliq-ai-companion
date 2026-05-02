package com.cliq.plugin.diff

/**
 * Result of a diff review interaction. Emitted via [CliqDiffManager.DiffListener]
 * so callers (e.g. an MCP bridge talking to a CLI agent) can react without
 * needing to subclass the diff manager itself.
 */
sealed interface CliqDiffOutcome {
    val filePath: String

    /** User accepted the proposed text. [finalContent] reflects any in-place edits. */
    data class Accepted(override val filePath: String, val finalContent: String) : CliqDiffOutcome

    /** User dismissed the diff without writing changes. */
    data class Rejected(override val filePath: String, val suppressed: Boolean = false) : CliqDiffOutcome
}
