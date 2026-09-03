package com.cliq.plugin.terminal

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.agents.CliAgentDefinition
import com.cliq.plugin.util.CliPathEscaper
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

@Service(Service.Level.PROJECT)
class CliqTerminalLauncher(private val project: Project) {

    private val log = logger<CliqTerminalLauncher>()

    fun launch(agent: CliAgentDefinition) {
        val executable = agent.executablePath.trim()
        if (executable.isEmpty()) {
            notify(
                "No command configured",
                "Set a CLI executable for ${agent.displayName} in Settings | Tools | Cliq.",
                NotificationType.WARNING,
            )
            return
        }

        val workDirectory = agent.workingDirectory.trim().ifBlank { project.basePath }
        val shellCommand = buildShellCommand(agent, executable, workDirectory)

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
                    workDirectory ?: project.basePath,
                    "Cliq · ${agent.displayName}",
                    true,
                    true,
                )
                ApplicationManager.getApplication().invokeLater {
                    try {
                        widget.sendCommandToExecute(shellCommand)
                    } catch (t: Throwable) {
                        log.warn("Failed to send command to widget", t)
                    }
                }
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

    private fun buildShellCommand(agent: CliAgentDefinition, executable: String, workDirectory: String?): String {
        val commandLine = GeneralCommandLine(executable)
        val arguments = ParametersListUtil.parse(agent.argumentsTemplate)
        commandLine.addParameters(arguments)

        return buildString {
            append(environmentPrefix(agent.environmentVariables))
            append(commandLine.commandLineString)
        }
    }

    private fun environmentPrefix(environmentVariables: Map<String, String>): String {
        if (environmentVariables.isEmpty()) return ""
        return if (SystemInfo.isWindows) {
            environmentVariables.entries.joinToString(separator = "") { (key, value) -> "set \"$key=$value\" && " }
        } else {
            environmentVariables.entries.joinToString(separator = " ", postfix = " ") { (key, value) ->
                "$key=${CliPathEscaper.escape(value)}"
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
