package com.cliq.plugin.settings

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Opens the Cliq page inside the IDE's Settings dialog. Thin wrapper around
 * [ShowSettingsUtil] – the only reason this exists is so the action can be
 * placed in the Tools menu next to the launcher.
 */
class OpenCliqSettingsAction : AnAction() {

    // Newer SDK requires explicitly declaring which thread `update` runs on.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, CliqSettingsConfigurable::class.java)
    }
}
