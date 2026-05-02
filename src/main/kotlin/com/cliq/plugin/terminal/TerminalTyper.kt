package com.cliq.plugin.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Alarm
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Component
import java.awt.Container

/**
 * Types text into the currently-selected terminal tab without appending a newline,
 * so the text lands in the active CLI prompt rather than being executed.
 */
internal object TerminalTyper {

    private val log = logger<TerminalTyper>()

    fun typeInActiveTerminal(project: Project, text: String, execute: Boolean = false) {
        ApplicationManager.getApplication().invokeLater {
            val terminalWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return@invokeLater
            if (terminalWindow.isVisible) {
                sendToContent(terminalWindow.contentManager.selectedContent?.component, text, execute)
            } else {
                terminalWindow.show {
                    sendToContent(terminalWindow.contentManager.selectedContent?.component, text, execute)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendToContent(component: Component?, text: String, execute: Boolean) {
        val starter = findJediTermWidget(component ?: return)?.terminalStarter ?: return

        val isMultiline = text.contains("\n")
        val payload = buildString {
            if (isMultiline) append("\u001B[200~") // Start paste
            append(text)
            if (isMultiline) append("\u001B[201~") // End paste
        }

        // Отправляем текст
        starter.sendString(payload, false)

        if (execute) {
            // Добавляем микро-задержку в 50мс перед нажатием Enter.
            // Это гарантированно заставит Node.js CLI-агента зарегистрировать нажатие.
            Alarm().addRequest({
                starter.sendString("\r", true)
            }, 50)
        }
    }

    private fun findJediTermWidget(component: Component): JediTermWidget? {
        if (component is JediTermWidget) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findJediTermWidget(child)
                if (found != null) return found
            }
        }
        return null
    }
}
