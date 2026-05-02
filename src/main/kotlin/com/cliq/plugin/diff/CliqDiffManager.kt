package com.cliq.plugin.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.openapi.diff.DiffBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.messages.Topic
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.EventListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-scoped service that owns the lifecycle of Cliq diff reviews.
 *
 * For each open review we:
 *   1. Build a `SimpleDiffRequest` with the current file content on the left
 *      (read-only) and the agent-proposed content on the right (editable, so
 *      the user can tweak suggestions before accepting).
 *   2. Attach **Accept All Changes** and **Reject All Changes** actions via
 *      `DiffUserDataKeys.CONTEXT_ACTIONS`.
 *   3. Track the review by file path so subsequent calls (`accept`, `reject`,
 *      `closeDiff`) can locate the matching tab and resolve it.
 *
 * Accept writes the (possibly user-edited) right-side text to disk via the
 * VFS, closes the diff tab, and emits [CliqDiffOutcome.Accepted]. Reject just
 * closes the tab and emits [CliqDiffOutcome.Rejected]; the original file is
 * untouched.
 */
@Service(Service.Level.PROJECT)
class CliqDiffManager(private val project: Project) : Disposable {

    private val log = logger<CliqDiffManager>()
    private val reviews = mutableMapOf<String, ReviewState>()
    private val listeners = CopyOnWriteArrayList<DiffListener>()

    interface DiffListener : EventListener {
        fun onDiffOutcome(outcome: CliqDiffOutcome)
    }

    companion object {
        /** Stored on the diff request so we can find it later by file path. */
        val FILE_PATH_KEY: Key<String> = Key.create("cliq.diff.filePath")
        val TOPIC: Topic<DiffListener> = Topic.create("Cliq Diff Outcome", DiffListener::class.java)
    }

    init {
        // Listen for tab closures. If a Cliq review tab is closed by the user
        // (rather than our `closeFile` call), we treat it as a rejection.
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file is ChainDiffVirtualFile) {
                    val producer = file.chain.requests.firstOrNull() as? SimpleDiffRequestChain.DiffRequestProducerWrapper
                    val path = producer?.request?.getUserData(FILE_PATH_KEY) ?: return
                    
                    // If the review state still exists, it means the tab was closed 
                    // manually (X button) or by other means, not by our `accept`/`reject`.
                    if (reviews.containsKey(path)) {
                        log.info("Diff tab for $path closed manually, rejecting review.")
                        reject(path)
                    }
                }
            }
        })
    }

    fun addListener(listener: DiffListener) { listeners.add(listener) }
    fun removeListener(listener: DiffListener) { listeners.remove(listener) }

    /**
     * Opens an interactive review for [filePath] that compares its current
     * on-disk contents (or empty, if [filePath] does not exist yet) against
     * [proposedContent].
     */
    fun showDiff(filePath: String, proposedContent: String) {
        val factory = DiffContentFactory.getInstance()
        val existing = LocalFileSystem.getInstance().findFileByPath(filePath)
        // Resolve a FileType by name even when the file does not yet exist on
        // disk — this is what makes the right pane highlight Go/PHP/JS source
        // for proposals that create new files.
        val fileType: FileType = existing?.fileType
            ?: FileTypeRegistry.getInstance().getFileTypeByFileName(Paths.get(filePath).fileName.toString())

        val left = if (existing != null) {
            factory.create(project, existing)
        } else {
            // Empty placeholder still typed so the left pane gets the same
            // highlighter as the right one for new-file proposals.
            factory.create("", fileType)
        }
        val right = factory.createEditable(project, proposedContent, fileType)

        val title = "${Paths.get(filePath).fileName} · Cliq review"
        val request = SimpleDiffRequest(title, left, right, "Current", "Proposed")

        request.putUserData(DiffUserDataKeys.MASTER_SIDE, Side.RIGHT)
        request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
        // Left = the file on disk, never directly editable from this view.
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, false))
        request.putUserData(FILE_PATH_KEY, filePath)

        // Hint the platform that the right pane is the "current/local" copy
        // and the left is the prior revision. This unlocks per-hunk gutter
        // arrows that revert (← reject) or apply (→ accept) individual
        // changes.
        request.putUserData(DiffUserDataKeysEx.LAST_REVISION_WITH_LOCAL, true)
        request.putUserData(DiffUserDataKeysEx.VCS_DIFF_ACCEPT_LEFT_ACTION_TEXT, DiffBundle.message("action.presentation.diff.revert.text"))

        // Mount the global review controls in the diff tab's toolbar.
        request.putUserData(
            DiffUserDataKeys.CONTEXT_ACTIONS,
            listOf(RejectCliqDiffAction(), AcceptCliqDiffAction()),
        )

        reviews[filePath] = ReviewState(filePath, proposedContent)

        val chain = SimpleDiffRequestChain(request)
        val virtualFile = ChainDiffVirtualFile(chain, title)
        val lastFocusedComponent = IdeFocusManager.getInstance(project).focusOwner
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            lastFocusedComponent?.let {
                IdeFocusManager.getInstance(project).requestFocus(it, true)
            }
        }
    }

    /**
     * Persists the right-side content to [filePath] and closes the review.
     * Idempotent: a no-op if no review is active for the path.
     */
    fun accept(filePath: String) {
        val review = reviews[filePath] ?: return

        val finalText = readRightSideText(filePath) ?: review.proposedContent
        try {
            writeFile(filePath, finalText)
        } catch (t: Throwable) {
            log.warn("Failed to write $filePath", t)
            notifyError("Failed to apply changes to $filePath: ${t.message ?: t.javaClass.simpleName}")
            return
        }

        finishReview(filePath, CliqDiffOutcome.Accepted(filePath, finalText))
    }

    fun applyDirectly(filePath: String, newContent: String) {
        try {
            writeFile(filePath, newContent)
            val outcome = CliqDiffOutcome.Accepted(filePath, newContent)
            listeners.forEach { runCatching { it.onDiffOutcome(outcome) }.onFailure { log.warn(it) } }
            project.messageBus.syncPublisher(TOPIC).onDiffOutcome(outcome)
        } catch (t: Throwable) {
            log.warn("Failed to auto-apply $filePath", t)
            notifyError("Failed to auto-apply changes to $filePath: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Cliq")
            .createNotification("Cliq diff", message, NotificationType.ERROR)
            .notify(project)
    }

    /** Closes the review without writing changes. */
    fun reject(filePath: String) {
        if (!reviews.containsKey(filePath)) return
        finishReview(filePath, CliqDiffOutcome.Rejected(filePath))
    }

    /**
     * External "close" trigger (e.g. CLI tells the IDE to drop the review
     * regardless of user action). Currently treated as rejection.
     */
    fun closeDiff(filePath: String, suppressNotification: Boolean = false): String? {
        val review = reviews[filePath] ?: return null
        val text = readRightSideText(filePath) ?: review.proposedContent
        finishReview(filePath, CliqDiffOutcome.Rejected(filePath, suppressNotification))
        return text
    }

    private fun finishReview(filePath: String, outcome: CliqDiffOutcome) {
        reviews.remove(filePath)
        closeDiffTab(filePath)
        listeners.forEach { runCatching { it.onDiffOutcome(outcome) }.onFailure { log.warn(it) } }
        project.messageBus.syncPublisher(TOPIC).onDiffOutcome(outcome)
    }

    private fun readRightSideText(filePath: String): String? {
        val virtualFile = findDiffVirtualFile(filePath) ?: return null
        val request = (virtualFile.chain.requests.firstOrNull()
            as? SimpleDiffRequestChain.DiffRequestProducerWrapper)?.request as? ContentDiffRequest
            ?: return null
        val rightContent = request.contents.getOrNull(1) as? DocumentContent ?: return null
        var text: String? = null
        ApplicationManager.getApplication().runReadAction { text = rightContent.document.text }
        return text
    }

    private fun closeDiffTab(filePath: String) {
        val virtualFile = findDiffVirtualFile(filePath) ?: return
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).closeFile(virtualFile)
        }
    }

    private fun findDiffVirtualFile(filePath: String): ChainDiffVirtualFile? {
        for (file in FileEditorManager.getInstance(project).openFiles) {
            if (file !is ChainDiffVirtualFile) continue
            val producer = file.chain.requests.firstOrNull() as? SimpleDiffRequestChain.DiffRequestProducerWrapper
                ?: continue
            if (producer.request.getUserData(FILE_PATH_KEY) == filePath) return file
        }
        return null
    }

    /**
     * Writes [text] to [path], creating the file (and its parent directories)
     * if necessary. Performed under a write command so undo history records it.
     */
    private fun writeFile(path: String, text: String) {
        val ioFile = File(path)
        val parent = ioFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        val existing = LocalFileSystem.getInstance().findFileByPath(path)
        if (existing != null) {
            WriteCommandAction.runWriteCommandAction(project, "Cliq: Apply Suggestion", null, {
                existing.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
            })
            return
        }

        // New file path — create through the VFS so file system listeners fire.
        WriteAction.runAndWait<Throwable> {
            if (!ioFile.exists()) ioFile.createNewFile()
            val virtual: VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            virtual?.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    override fun dispose() {
        listeners.clear()
        reviews.clear()
    }

    private data class ReviewState(
        val filePath: String,
        val proposedContent: String,
    )
}
