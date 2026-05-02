package com.cliq.plugin.diff

import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

abstract class CliqDiffToolbarAction(text: String, description: String, icon: javax.swing.Icon) :
    AnAction(text, description, icon) {

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = filePathFromContext(e) != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = filePathFromContext(e) ?: return
        run(project.service<CliqDiffManager>(), path)
    }

    protected abstract fun run(manager: CliqDiffManager, filePath: String)

    private fun filePathFromContext(e: AnActionEvent): String? {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) as? ChainDiffVirtualFile ?: return null
        val requestFromFile = (virtualFile.chain.requests.firstOrNull()
            as? SimpleDiffRequestChain.DiffRequestProducerWrapper)?.request ?: return null
        return requestFromFile.getUserData(CliqDiffManager.FILE_PATH_KEY)
    }
}

class AcceptCliqDiffAction :
    CliqDiffToolbarAction("Accept All Changes", "Apply all of Cliq's proposed changes", AllIcons.Actions.Checked) {
    override fun run(manager: CliqDiffManager, filePath: String) = manager.accept(filePath)
}

class RejectCliqDiffAction :
    CliqDiffToolbarAction("Reject All Changes", "Discard all of Cliq's proposed changes", AllIcons.Actions.Cancel) {
    override fun run(manager: CliqDiffManager, filePath: String) = manager.reject(filePath)
}
