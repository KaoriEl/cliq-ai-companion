package com.cliq.plugin.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Component
import java.awt.Container

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
        try {
            val widget = findJediTermWidget(component ?: return) ?: return
            val starter = widget.terminalStarter ?: return

            val isMultiline = text.contains("\n")
            val payload = buildString {
                if (isMultiline) append("\u001B[200~")
                append(text)
                if (isMultiline) append("\u001B[201~")
                if (execute) append("\r")
            }

            starter.sendString(payload, false)
        } catch (e: Exception) {
            log.warn(e)
        }
    }

    private fun findJediTermWidget(component: Component, depth: Int = 0): JediTermWidget? {
        if (depth > 32) return null
        if (component is JediTermWidget) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findJediTermWidget(child, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }
}
