package com.cliq.plugin.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Component
import java.awt.Container

/**
 * Types text into the currently-selected terminal tab without appending a newline,
 * so the text lands in the active CLI prompt rather than being executed.
 */
internal object TerminalTyper {

    private val log = logger<TerminalTyper>()

    fun typeInActiveTerminal(project: Project, text: String) {
        ApplicationManager.getApplication().invokeLater {
            val terminalWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return@invokeLater
            if (terminalWindow.isVisible) {
                sendToContent(terminalWindow.contentManager.selectedContent?.component, text)
            } else {
                terminalWindow.show {
                    sendToContent(terminalWindow.contentManager.selectedContent?.component, text)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendToContent(component: Component?, text: String) {
        if (component == null) {
            log.warn("No active terminal content to type into")
            return
        }
        val widget = findJediTermWidget(component)
        if (widget == null) {
            log.warn("Could not find JediTermWidget in terminal content hierarchy")
            return
        }
        val starter = widget.terminalStarter ?: run {
            log.warn("TerminalStarter is null — session not yet attached")
            return
        }
        starter.sendString(text, false)
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
