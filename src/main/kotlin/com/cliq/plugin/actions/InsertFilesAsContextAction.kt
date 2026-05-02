package com.cliq.plugin.actions

import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.util.PathUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware

/**
 * Converts the currently selected Project View files (or open editor files if
 * nothing is selected) into @-tokens and types them into the active terminal
 * prompt without appending a newline. Default shortcut: Shift+B.
 */
class InsertFilesAsContextAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.isNotEmpty() == true
            || e.getData(CommonDataKeys.VIRTUAL_FILE) != null
            || (project != null && FileEditorManager.getInstance(project).openFiles.isNotEmpty())
        e.presentation.isEnabledAndVisible = project != null && hasFiles
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val files =
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }
                ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
                ?: FileEditorManager.getInstance(project).openFiles.takeIf { it.isNotEmpty() }
                ?: return

        val tokens = files
            .filter { it.isValid }
            .mapNotNull { PathUtil.toRelativePosix(basePath, it.path) }
            .filter { it.isNotBlank() }
            .joinToString(" ") { "@$it" }

        if (tokens.isBlank()) return
        TerminalTyper.typeInActiveTerminal(project, "$tokens ")
    }
}
