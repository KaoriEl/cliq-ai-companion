package com.cliq.plugin.toolwindow

import com.cliq.plugin.agents.Agent
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.settings.CliqSettingsConfigurable
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.util.PathUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Default content of the Cliq tool window.
 *
 * Provides a one-click launcher for each configured agent, a collapsible
 * Files Context panel for building a set of @-references to inject into the
 * active terminal, and a shortcut to the settings page.
 */
class CliqToolWindowPanel(private val project: Project) : JBPanel<CliqToolWindowPanel>(BorderLayout()) {

    init {
        val settings = CliqSettings.getInstance()
        val launcher = project.service<CliqTerminalLauncher>()
        val basePath = project.basePath

        val autoApplyBox = JCheckBox("Auto-apply changes without review", settings.autoApplyChanges).apply {
            addActionListener { settings.autoApplyChanges = isSelected }
        }

        // ── Files Context state ──────────────────────────────────────────────
        val pickedFilesModel = CollectionListModel<VirtualFile>()
        val pickedFilesList = JBList(pickedFilesModel).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            visibleRowCount = 6
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(
                    list,
                    displayPath(value as? VirtualFile, basePath),
                    index, isSelected, cellHasFocus,
                )
            }
        }

        fun chooseAndAdd() {
            val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                .withTitle("Select Files for Context")
            val chosen = FileChooser.chooseFiles(descriptor, project, null)
            chosen.forEach { vf ->
                if (pickedFilesModel.items.none { it.path == vf.path }) {
                    pickedFilesModel.add(vf)
                }
            }
        }

        fun insertIntoTerminal() {
            val items: List<VirtualFile> = pickedFilesList.selectedValuesList
                .ifEmpty { pickedFilesModel.items }
            if (items.isEmpty()) return
            val tokens = items
                .mapNotNull { vf -> PathUtil.toRelativePosix(basePath, vf.path) }
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return
            TerminalTyper.typeInActiveTerminal(project, tokens.joinToString(" ") { "@$it" } + " ")
        }
        // ────────────────────────────────────────────────────────────────────

        val body = panel {
            row {
                cell(JBLabel("<html><b>Cliq AI Companion</b><br/>Launch a configured CLI agent in the integrated terminal.</html>"))
            }
            settings.agents().forEach { agent ->
                row {
                    cell(launchButton(agent) { launcher.launch(it) })
                }
            }

            collapsibleGroup("Files Context") {
                row {
                    cell(JBScrollPane(pickedFilesList))
                        .resizableColumn()
                        .align(Align.FILL)
                }
                row {
                    button("+ Add files…") { chooseAndAdd() }
                    button("Remove") {
                        pickedFilesList.selectedValuesList.forEach { pickedFilesModel.remove(it) }
                    }
                    button("Clear") { pickedFilesModel.removeAll() }
                    button("Insert into Terminal") { insertIntoTerminal() }
                }
                row {
                    comment("Adds selected (or all) files as @-references into the active terminal. No newline is sent.")
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

    private fun displayPath(vf: VirtualFile?, basePath: String?): String {
        if (vf == null) return ""
        return PathUtil.toRelativePosix(basePath, vf.path) ?: vf.path
    }
}
