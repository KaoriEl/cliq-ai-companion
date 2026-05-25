package com.cliq.plugin.actions

import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.util.CliPathEscaper
import com.cliq.plugin.util.PathUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware

class InsertFilesAsContextAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val hasSelection = (selectedFiles != null && selectedFiles.isNotEmpty()) || singleFile != null
        val isEditor = e.getData(CommonDataKeys.EDITOR) != null
        val isValidContext = !isEditor

        e.presentation.isEnabledAndVisible = project != null && hasSelection && isValidContext
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val files =
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }
                ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
                ?: return

        val tokens = files
            .filter { it.isValid }
            .mapNotNull { PathUtil.toRelativePosix(basePath, it.path) }
            .joinToString(" ") { "@${CliPathEscaper.escape(it)}" }

        if (tokens.isBlank()) return
        TerminalTyper.typeInActiveTerminal(project, " $tokens ")
    }
}
