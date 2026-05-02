package com.cliq.plugin.diff

sealed interface CliqDiffOutcome {
    val filePath: String

    data class Accepted(override val filePath: String, val finalContent: String) : CliqDiffOutcome

    data class Rejected(override val filePath: String, val suppressed: Boolean = false) : CliqDiffOutcome
}
