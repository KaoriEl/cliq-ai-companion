package com.cliq.plugin.agents

/**
 * A single agent profile: the human-readable label shown in pickers and the
 * shell command that actually launches the CLI.
 *
 * The `command` is intentionally a free-form string (not `List<String>`) so the
 * user can plug in shell features like `env FOO=bar claude --model opus`.
 * It is forwarded verbatim to the integrated terminal which handles parsing.
 */
data class Agent(val id: String, val displayName: String, val command: String) {
    companion object {
        const val CLAUDE_ID = "claude"
        const val GEMINI_ID = "gemini"
    }
}
