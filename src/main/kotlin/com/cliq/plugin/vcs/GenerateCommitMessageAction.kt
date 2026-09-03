package com.cliq.plugin.vcs

import com.cliq.plugin.toolwindow.CliqToolWindowPanel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.ToolWindowManager
import java.io.StringWriter

class GenerateCommitMessageAction : AnAction() {

    companion object {
        private const val MAX_DIFF_LENGTH = 20_000
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changes = ChangeListManager.getInstance(project).allChanges
        if (changes.isEmpty()) {
            notify(project, "No changes found", "There are no pending changes to summarize.", NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Cliq: Building Diff for Commit Message", false) {
            override fun run(indicator: ProgressIndicator) {
                val diffText = runCatching { buildDiffText(project, changes) }.getOrNull()
                if (diffText.isNullOrBlank()) {
                    notify(project, "Could not build diff", "Failed to build a diff from the current changes.", NotificationType.ERROR)
                    return
                }

                val prompt = buildPrompt(diffText)
                ApplicationManager.getApplication().invokeLater {
                    ToolWindowManager.getInstance(project).getToolWindow("Cliq")?.show {
                        project.messageBus.syncPublisher(CliqToolWindowPanel.INSERT_PROMPT_TOPIC)
                            .onInsertPromptTextRequested(prompt)
                    }
                }
            }
        })
    }

    private fun buildDiffText(project: Project, changes: Collection<Change>): String {
        val basePath = project.basePath ?: "."
        val patches = IdeaTextPatchBuilder.buildPatch(project, changes, basePath, false)
        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches, writer, "\n", null)
        val text = writer.toString()
        return if (text.length > MAX_DIFF_LENGTH) text.take(MAX_DIFF_LENGTH) + "\n... [diff truncated]" else text
    }

    private fun buildPrompt(diffText: String): String = buildString {
        append("Write a concise commit message for the following changes. ")
        append("Use the imperative mood for the summary line (max ~72 characters), ")
        append("followed by an optional short body explaining the why:\n\n")
        append("```diff\n")
        append(diffText)
        append("\n```")
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Cliq")
            .createNotification(title, content, type)
            .notify(project)
    }
}
