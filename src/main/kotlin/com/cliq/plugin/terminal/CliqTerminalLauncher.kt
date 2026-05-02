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

@Service(Service.Level.PROJECT)
class CliqTerminalLauncher(private val project: Project) {

    private val log = logger<CliqTerminalLauncher>()

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

        terminalWindow.show {
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val widget = manager.createShellWidget(
                    project.basePath,
                    "Cliq · ${agent.displayName}",
                    true,
                    true,
                )
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
