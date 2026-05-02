package com.cliq.plugin.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Builds the right-side "Cliq" tool window.
 *
 * `DumbAware` lets the window appear before indexing finishes – the panel does
 * not touch any indexes itself, so this is safe.
 */
class CliqToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CliqToolWindowPanel(project)
        // `ContentFactory.getInstance()` replaces the deprecated
        // `ContentFactory.SERVICE.getInstance()` static accessor.
        val content = ContentFactory.getInstance().createContent(panel, /* displayName = */ "", /* isLockable = */ false)
        toolWindow.contentManager.addContent(content)
    }
}
