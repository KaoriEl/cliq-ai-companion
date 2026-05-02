package com.cliq.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CliqSettingsConfigurable : Configurable {

    private val settings = CliqSettings.getInstance()

    private var claudeField: JBTextField? = null
    private var geminiField: JBTextField? = null
    private var autoApplyBox: javax.swing.JCheckBox? = null

    override fun getDisplayName(): String = "Cliq"

    override fun createComponent(): JComponent {
        val claude = JBTextField(settings.claudeCommand, 30).also { claudeField = it }
        val gemini = JBTextField(settings.geminiCommand, 30).also { geminiField = it }
        val autoApply = javax.swing.JCheckBox("Auto-apply changes without review", settings.autoApplyChanges).also { autoApplyBox = it }

        return panel {
            row("Claude CLI command:") {
                cell(claude).columns(30)
            }.layout(RowLayout.LABEL_ALIGNED)
            row {
                comment("Executable name (resolved via PATH) or absolute path. Example: claude, /usr/local/bin/claude")
            }
            row("Gemini CLI command:") {
                cell(gemini).columns(30)
            }.layout(RowLayout.LABEL_ALIGNED)
            row {
                comment("Executable name or absolute path. Example: gemini, /opt/homebrew/bin/gemini")
            }
            row {
                cell(autoApply)
            }
            row {
                comment("If enabled, proposed code changes will be written directly to disk.")
            }
        }
    }

    override fun isModified(): Boolean {
        val claude = claudeField?.text ?: return false
        val gemini = geminiField?.text ?: return false
        val autoApply = autoApplyBox?.isSelected ?: return false
        return claude != settings.claudeCommand || gemini != settings.geminiCommand || autoApply != settings.autoApplyChanges
    }

    override fun apply() {
        val claude = claudeField?.text?.trim().orEmpty()
        val gemini = geminiField?.text?.trim().orEmpty()
        val autoApply = autoApplyBox?.isSelected ?: false
        if (claude.isEmpty() && gemini.isEmpty()) {
            throw ConfigurationException("At least one CLI command must be configured.")
        }
        settings.claudeCommand = claude
        settings.geminiCommand = gemini
        settings.autoApplyChanges = autoApply
    }

    override fun reset() {
        claudeField?.text = settings.claudeCommand
        geminiField?.text = settings.geminiCommand
        autoApplyBox?.isSelected = settings.autoApplyChanges
    }

    override fun disposeUIResources() {
        claudeField = null
        geminiField = null
        autoApplyBox = null
    }
}
