package com.cliq.plugin.agents

data class Agent(val id: String, val displayName: String, val command: String) {
    companion object {
        const val CLAUDE_ID = "claude"
        const val GEMINI_ID = "gemini"
    }
}
