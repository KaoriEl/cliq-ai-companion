package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CliAgentEditDialog(
    private val initial: CliAgentDefinition,
    private val existingDisplayNames: Set<String>,
) : DialogWrapper(true) {

    private val displayNameField = JBTextField(initial.displayName, 30)
    private val executableField = JBTextField(initial.executablePath, 30)
    private val argumentsField = JBTextField(initial.argumentsTemplate, 30)
    private val workingDirectoryField = JBTextField(initial.workingDirectory, 30)
    private val environmentArea = JBTextArea(
        initial.environmentVariables.entries.joinToString("\n") { (key, value) -> "$key=$value" },
        6,
        30,
    )

    init {
        setTitle(if (initial.displayName.isBlank()) "Add CLI Agent" else "Edit CLI Agent")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Display name:") {
            cell(displayNameField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row("Executable path:") {
            cell(executableField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Executable name resolved via PATH, or an absolute path to the CLI binary.")
        }
        row("Arguments:") {
            cell(argumentsField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Space-separated CLI flags, e.g. --model sonnet --yolo. Quote values containing spaces.")
        }
        row("Working directory:") {
            cell(workingDirectoryField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Leave empty to use the current project's root directory.")
        }
        row("Environment variables:") {}
        row {
            cell(JBScrollPane(environmentArea))
        }
        row {
            comment("One KEY=VALUE pair per line.")
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = displayNameField.text.trim()
        val executable = executableField.text.trim()
        if (name.isEmpty()) return ValidationInfo("Display name must not be empty.", displayNameField)
        if (executable.isEmpty()) return ValidationInfo("Executable path must not be empty.", executableField)
        if (name != initial.displayName && existingDisplayNames.contains(name)) {
            return ValidationInfo("An agent named '$name' already exists.", displayNameField)
        }
        if (parseEnvironment() == null) {
            return ValidationInfo("Environment variables must be in KEY=VALUE format, one per line.", environmentArea)
        }
        return null
    }

    private fun parseEnvironment(): MutableMap<String, String>? {
        val result = mutableMapOf<String, String>()
        environmentArea.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) return null
                val key = line.substring(0, separatorIndex).trim()
                val value = line.substring(separatorIndex + 1).trim()
                if (key.isEmpty()) return null
                result[key] = value
            }
        return result
    }

    fun buildResult(): CliAgentDefinition = initial.copy(
        displayName = displayNameField.text.trim(),
        executablePath = executableField.text.trim(),
        argumentsTemplate = argumentsField.text.trim(),
        workingDirectory = workingDirectoryField.text.trim(),
        environmentVariables = parseEnvironment() ?: mutableMapOf(),
    )
}
