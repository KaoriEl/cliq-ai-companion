package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.AnActionButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class CliqSettingsConfigurable : Configurable {

    private val settings = CliqSettings.getInstance()

    private val workingAgents: MutableList<CliAgentDefinition> = mutableListOf()
    private var tableModel: CliAgentTableModel? = null
    private var table: JBTable? = null
    private var autoApplyBox: javax.swing.JCheckBox? = null

    override fun getDisplayName(): String = "Cliq"

    override fun createComponent(): JComponent {
        workingAgents.clear()
        workingAgents.addAll(cloneAgents(settings.agents()))
        val model = CliAgentTableModel(workingAgents).also { tableModel = it }

        val agentsTable = JBTable(model).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            rowHeight = JBUI.scale(22)
        }
        table = agentsTable

        val tablePanel = ToolbarDecorator.createDecorator(agentsTable)
            .setAddAction { addAgent() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .setEditActionUpdater { agentsTable.selectedRow >= 0 }
            .setRemoveActionUpdater { agentsTable.selectedRow >= 0 }
            .addExtraAction(object : AnActionButton("Duplicate", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) = duplicateSelected()
                override fun isEnabled(): Boolean = agentsTable.selectedRow >= 0
            })
            .disableUpDownActions()
            .createPanel()

        val autoApply = javax.swing.JCheckBox("Auto-apply changes without review", settings.autoApplyChanges)
            .also { autoApplyBox = it }

        val bottomPanel = panel {
            row {
                cell(autoApply)
            }
            row {
                comment("If enabled, proposed code changes will be written directly to disk.")
            }
            separator()
            row("Keyboard Shortcuts:") {
                link("Configure shortcuts in Keymap...") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, "Keymap")
                }
            }
            row {
                comment("Search for 'Cliq: Send Files' to assign a custom shortcut.")
            }
        }

        return JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(4)
            add(tablePanel, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
    }

    private fun cloneAgents(agents: List<CliAgentDefinition>): List<CliAgentDefinition> =
        agents.map { it.copy(environmentVariables = it.environmentVariables.toMutableMap()) }

    private fun addAgent() {
        val model = tableModel ?: return
        val existingNames = model.snapshot().map { it.displayName }.toSet()
        val dialog = CliAgentEditDialog(CliAgentDefinition(), existingNames)
        if (dialog.showAndGet()) {
            model.addAgent(dialog.buildResult())
        }
    }

    private fun editSelected() {
        val model = tableModel ?: return
        val row = table?.selectedRow ?: return
        if (row < 0) return
        val current = model.agentAt(row)
        val existingNames = model.snapshot()
            .filterIndexed { index, _ -> index != row }
            .map { it.displayName }
            .toSet()
        val dialog = CliAgentEditDialog(current, existingNames)
        if (dialog.showAndGet()) {
            model.updateAgent(row, dialog.buildResult())
        }
    }

    private fun duplicateSelected() {
        val model = tableModel ?: return
        val row = table?.selectedRow ?: return
        if (row < 0) return
        val source = model.agentAt(row)
        val existingNames = model.snapshot().map { it.displayName }.toSet()

        var candidateName = "${source.displayName} (copy)"
        var suffix = 2
        while (candidateName in existingNames) {
            candidateName = "${source.displayName} (copy $suffix)"
            suffix++
        }

        model.addAgent(
            source.copy(
                id = UUID.randomUUID().toString(),
                displayName = candidateName,
                environmentVariables = source.environmentVariables.toMutableMap(),
            )
        )
    }

    private fun removeSelected() {
        val model = tableModel ?: return
        val row = table?.selectedRow ?: return
        if (row < 0) return
        model.removeAgent(row)
    }

    override fun isModified(): Boolean {
        val model = tableModel ?: return false
        val autoApply = autoApplyBox?.isSelected ?: return false
        return model.snapshot() != settings.agents() || autoApply != settings.autoApplyChanges
    }

    override fun apply() {
        val model = tableModel ?: return
        val autoApply = autoApplyBox?.isSelected ?: false
        val agents = model.snapshot()

        if (agents.isEmpty()) {
            throw ConfigurationException("At least one CLI agent must be configured.")
        }

        val invalidAgent = agents.firstOrNull { !it.isValid() }
        if (invalidAgent != null) {
            val label = invalidAgent.displayName.ifBlank { "(unnamed)" }
            throw ConfigurationException("Agent '$label' requires both a display name and an executable path.")
        }

        val duplicateGroup = agents.groupBy { it.displayName.trim() }.values.firstOrNull { it.size > 1 }
        if (duplicateGroup != null) {
            throw ConfigurationException("Agent names must be unique. Duplicate name: '${duplicateGroup.first().displayName}'.")
        }

        settings.setAgents(agents)
        settings.autoApplyChanges = autoApply
    }

    override fun reset() {
        workingAgents.clear()
        workingAgents.addAll(cloneAgents(settings.agents()))
        tableModel?.replaceAll(workingAgents)
        autoApplyBox?.isSelected = settings.autoApplyChanges
    }

    override fun disposeUIResources() {
        tableModel = null
        table = null
        autoApplyBox = null
    }
}
