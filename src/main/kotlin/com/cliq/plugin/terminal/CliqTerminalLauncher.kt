package com.cliq.plugin.terminal

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.agents.Agent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Project-scoped service that knows how to launch a CLI agent inside the IDE's
 * built-in terminal.
 *
 * The flow is intentionally simple:
 *   1. Look up the bundled "Terminal" tool window.
 *   2. Create a fresh shell widget rooted at the project directory.
 *   3. Send the configured command line to the shell, which handles parsing
 *      and starts the underlying process. We deliberately do not spawn the
 *      process via `ProcessBuilder` – piggy-backing on the integrated terminal
 *      gives us a fully interactive PTY (colours, tab completion, raw mode)
 *      for free.
 */
@Service(Service.Level.PROJECT)
class CliqTerminalLauncher(private val project: Project) {

    private val log = logger<CliqTerminalLauncher>()

    /**
     * Launches [agent] in a new tab of the integrated terminal.
     *
     * @param agent the resolved agent profile carrying the shell command to run
     */
    fun launch(agent: Agent) {
        val command = agent.command.trim()
        if (command.isEmpty()) {
            notify(
                "No command configured",
                "Set a CLI executable for ${agent.displayName} in Settings | Tools | Cliq.",
                NotificationType.WARNING,
            )
            return
        }

        val terminalWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        if (terminalWindow == null) {
            notify(
                "Terminal unavailable",
                "The bundled Terminal tool window could not be located.",
                NotificationType.ERROR,
            )
            return
        }

        // `show` ensures the tool window is visible and queues our callback on
        // the EDT after layout completes. Spawning the widget before the window
        // is shown can cause focus glitches.
        terminalWindow.show {
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val widget = manager.createShellWidget(
                    /* workingDirectory = */ project.basePath,
                    /* tabName          = */ "Cliq · ${agent.displayName}",
                    /* requestFocus     = */ true,
                    /* deferSessionStartUntilUiShown = */ true,
                )
                // `sendCommandToExecute` is the supported replacement for the
                // deprecated `executeCommand` shortcut – it appends a newline
                // and sends the line through the PTY exactly like a user keystroke.
                widget.sendCommandToExecute(command)
            } catch (t: Throwable) {
                log.warn("Failed to launch ${agent.displayName} via terminal", t)
                notify(
                    "Failed to launch ${agent.displayName}",
                    t.message ?: "Unknown error while creating terminal session.",
                    NotificationType.ERROR,
                )
            }
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
