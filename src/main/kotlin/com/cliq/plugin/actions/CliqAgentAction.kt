package com.cliq.plugin.actions

import com.cliq.plugin.agents.Agent
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.popup.JBPopupFactory

/**
 * Entry point shown in the Tools menu, the right-side main toolbar and via
 * the Ctrl+Alt+Q shortcut.
 *
 * The action does not run any CLI itself: it shows a popup chooser of agents
 * defined in [CliqSettings], and delegates the actual launch to
 * [CliqTerminalLauncher]. Splitting "what to run" from "how to run it" keeps
 * the action thin and lets the launcher be reused (for example by a future
 * tool window button).
 */
class CliqAgentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Disable the action when no project is open – we need a base directory
        // and a tool window to attach the terminal to.
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val launcher = project.service<CliqTerminalLauncher>()
        val agents = CliqSettings.getInstance().agents()

        when (agents.size) {
            0 -> return
            1 -> launcher.launch(agents.single())
            else -> showAgentPicker(e, agents) { launcher.launch(it) }
        }
    }

    private fun showAgentPicker(
        e: AnActionEvent,
        agents: List<Agent>,
        onChosen: (Agent) -> Unit,
    ) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(agents)
            .setTitle("Run Cliq Agent")
            .setItemChosenCallback { chosen -> onChosen(chosen) }
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel("${value.displayName}  —  ${value.command}").apply {
                    border = javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)
                }
            }
            .createPopup()

        // Anchor to the action's source component when invoked from a toolbar,
        // otherwise centre on the focused window.
        val component = e.inputEvent?.component
        if (component != null) {
            popup.showUnderneathOf(component)
        } else {
            popup.showCenteredInCurrentWindow(e.project!!)
        }
    }
}
