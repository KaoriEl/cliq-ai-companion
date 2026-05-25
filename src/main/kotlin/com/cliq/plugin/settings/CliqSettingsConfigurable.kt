package com.cliq.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CliqSettingsConfigurable : Configurable {

    private val settings = CliqSettings.getInstance()

    private var claudeField: JBTextField? = null
    private var geminiField: JBTextField? = null
    private var qwenField: JBTextField? = null
    private var autoApplyBox: javax.swing.JCheckBox? = null

    override fun getDisplayName(): String = "Cliq"

    override fun createComponent(): JComponent {
        val claude = JBTextField(settings.claudeCommand, 30).also { claudeField = it }
        val gemini = JBTextField(settings.geminiCommand, 30).also { geminiField = it }
        val qwen   = JBTextField(settings.qwenCommand,   30).also { qwenField   = it }
        val autoApply = javax.swing.JCheckBox("Auto-apply changes without review", settings.autoApplyChanges)
            .also { autoApplyBox = it }

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
            row("Qwen CLI command:") {
                cell(qwen).columns(30)
            }.layout(RowLayout.LABEL_ALIGNED)
            row {
                comment("Executable name or absolute path for Qwen Code CLI. Example: qwen")
            }
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
    }

    override fun isModified(): Boolean {
        val claude = claudeField?.text ?: return false
        val gemini = geminiField?.text ?: return false
        val qwen   = qwenField?.text   ?: return false
        val autoApply = autoApplyBox?.isSelected ?: return false
        return claude != settings.claudeCommand
            || gemini != settings.geminiCommand
            || qwen   != settings.qwenCommand
            || autoApply != settings.autoApplyChanges
    }

    override fun apply() {
        val claude = claudeField?.text?.trim().orEmpty()
        val gemini = geminiField?.text?.trim().orEmpty()
        val qwen   = qwenField?.text?.trim().orEmpty()
        val autoApply = autoApplyBox?.isSelected ?: false
        if (claude.isEmpty() && gemini.isEmpty() && qwen.isEmpty()) {
            throw ConfigurationException("At least one CLI command must be configured.")
        }
        settings.claudeCommand = claude
        settings.geminiCommand = gemini
        settings.qwenCommand   = qwen
        settings.autoApplyChanges = autoApply
    }

    override fun reset() {
        claudeField?.text = settings.claudeCommand
        geminiField?.text = settings.geminiCommand
        qwenField?.text   = settings.qwenCommand
        autoApplyBox?.isSelected = settings.autoApplyChanges
    }

    override fun disposeUIResources() {
        claudeField = null
        geminiField = null
        qwenField   = null
        autoApplyBox = null
    }
}
