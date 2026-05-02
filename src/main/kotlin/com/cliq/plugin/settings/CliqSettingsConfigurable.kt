package com.cliq.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * UI for the Cliq settings page (Settings | Tools | Cliq).
 *
 * Built with the modern Kotlin UI DSL v2 (`panel { row { ... } }`) instead of
 * the older `FormBuilder`. Bindings keep the underlying [CliqSettings] in sync
 * via property references rather than ad-hoc field/state plumbing.
 */
class CliqSettingsConfigurable : Configurable {

    private val settings = CliqSettings.getInstance()

    private var claudeField: JBTextField? = null
    private var geminiField: JBTextField? = null

    override fun getDisplayName(): String = "Cliq"

    override fun createComponent(): JComponent {
        val claude = JBTextField(settings.claudeCommand, 30).also { claudeField = it }
        val gemini = JBTextField(settings.geminiCommand, 30).also { geminiField = it }

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
        }
    }

    override fun isModified(): Boolean {
        val claude = claudeField?.text ?: return false
        val gemini = geminiField?.text ?: return false
        return claude != settings.claudeCommand || gemini != settings.geminiCommand
    }

    override fun apply() {
        val claude = claudeField?.text?.trim().orEmpty()
        val gemini = geminiField?.text?.trim().orEmpty()
        if (claude.isEmpty() && gemini.isEmpty()) {
            throw ConfigurationException("At least one CLI command must be configured.")
        }
        settings.claudeCommand = claude
        settings.geminiCommand = gemini
    }

    override fun reset() {
        claudeField?.text = settings.claudeCommand
        geminiField?.text = settings.geminiCommand
    }

    override fun disposeUIResources() {
        claudeField = null
        geminiField = null
    }
}
