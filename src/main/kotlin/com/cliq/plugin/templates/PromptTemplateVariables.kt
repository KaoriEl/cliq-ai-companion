package com.cliq.plugin.templates

object PromptTemplateVariables {

    private val PLACEHOLDER_PATTERN = Regex("\\{\\{\\s*(\\w+)\\s*}}")

    fun resolve(content: String, activeFile: String?, selection: String?, clipboard: String?): String =
        PLACEHOLDER_PATTERN.replace(content) { match ->
            when (match.groupValues[1]) {
                "activeFile" -> activeFile.orEmpty()
                "selection" -> selection.orEmpty()
                "clipboard" -> clipboard.orEmpty()
                else -> match.value
            }
        }
}
