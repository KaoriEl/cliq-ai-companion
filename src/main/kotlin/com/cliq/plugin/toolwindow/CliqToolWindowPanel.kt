package com.cliq.plugin.toolwindow

import com.cliq.plugin.agents.Agent
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.settings.CliqSettingsConfigurable
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JCheckBox

/**
 * Default content of the Cliq tool window.
 *
 * Provides a one-click launcher for each configured agent plus a shortcut to
 * the settings page. The panel is rebuilt lazily on construction; if the user
 * changes commands in settings the next time the window is created it will
 * reflect the new values.
 */
class CliqToolWindowPanel(private val project: Project) : JBPanel<CliqToolWindowPanel>(BorderLayout()) {

    init {
        val settings = CliqSettings.getInstance()
        val launcher = project.service<CliqTerminalLauncher>()

        val autoApplyBox = JCheckBox("Auto-apply changes without review", settings.autoApplyChanges).apply {
            addActionListener { settings.autoApplyChanges = isSelected }
        }

        val body = panel {
            row {
                cell(JBLabel("<html><b>Cliq AI Companion</b><br/>Launch a configured CLI agent in the integrated terminal.</html>"))
            }
            settings.agents().forEach { agent ->
                row {
                    cell(launchButton(agent) { launcher.launch(it) })
                }
            }
            row {
                cell(autoApplyBox)
            }
            row {
                comment("If enabled, proposed code changes will be written directly to disk.")
            }
            row {
                cell(JButton("Open Settings…").apply {
                    addActionListener {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, CliqSettingsConfigurable::class.java)
                    }
                })
            }
        }
        add(body, BorderLayout.NORTH)
    }

    private fun launchButton(agent: Agent, onClick: (Agent) -> Unit): JButton =
        JButton("Run ${agent.displayName}").apply {
            toolTipText = "Executes: ${agent.command}"
            addActionListener { onClick(agent) }
        }
}
