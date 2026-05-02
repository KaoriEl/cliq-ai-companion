package com.cliq.plugin.terminal

import com.cliq.plugin.mcp.CliqIdeServer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

/**
 * Hook into every shell spawned in the IDE's integrated terminal to expose
 * the Cliq IDE server's coordinates as environment variables.
 *
 * The Gemini CLI looks at:
 *   - `GEMINI_CLI_IDE_SERVER_PORT` — port of the local MCP server
 *   - `GEMINI_CLI_IDE_AUTH_TOKEN` — bearer token
 *   - `GEMINI_CLI_IDE_WORKSPACE_PATH` — workspace path the server is bound to;
 *     used to detect the "Directory mismatch" condition when the CLI is started
 *     from a different working directory.
 *
 * The customizer fires for every new shell, so as long as the server has been
 * started (via `CliqStartupActivity`), these vars are always fresh.
 */
class CliqTerminalEnvCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        env: MutableMap<String, String>,
    ): Array<String> {
        val server = project.service<CliqIdeServer>()
        // The server may not yet be running on first project open if the
        // startup activity is still scheduled. Skip silently in that case —
        // the next opened shell will pick up the vars.
        server.port()?.let { env["GEMINI_CLI_IDE_SERVER_PORT"] = it.toString() }
        env["GEMINI_CLI_IDE_AUTH_TOKEN"] = server.authToken()
        if (server.workspacePath().isNotEmpty()) {
            env["GEMINI_CLI_IDE_WORKSPACE_PATH"] = server.workspacePath()
        }
        return command
    }
}
