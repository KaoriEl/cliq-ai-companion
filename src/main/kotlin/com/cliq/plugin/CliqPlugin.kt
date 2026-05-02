package com.cliq.plugin

/**
 * Plugin-wide constants shared by services, actions and the tool window.
 * Centralised here so that renames (notification group id, default CLI names, etc.)
 * touch a single file instead of leaking string literals across the code base.
 */
object CliqPlugin {
    const val PLUGIN_ID = "com.cliq.plugin"

    /** Notification group id declared in `plugin.xml`. */
    const val NOTIFICATION_GROUP = "Cliq"

    /** Default executable names; can be overridden in the settings page. */
    const val DEFAULT_CLAUDE_COMMAND = "claude"
    const val DEFAULT_GEMINI_COMMAND = "gemini"

    /** Tool window id declared in `plugin.xml` and used by `ToolWindowManager`. */
    const val TOOL_WINDOW_ID = "Cliq"
}
