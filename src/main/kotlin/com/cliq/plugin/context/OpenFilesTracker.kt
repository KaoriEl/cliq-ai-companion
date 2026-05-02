package com.cliq.plugin.context

import com.intellij.ide.impl.isTrusted
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.util.EventListener
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class OpenFilesTracker(private val project: Project) : Disposable {

    private val log = logger<OpenFilesTracker>()
    private val files = mutableListOf<MutableTrackedFile>()
    private val connection = project.messageBus.connect(this)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val listeners = CopyOnWriteArrayList<WorkspaceContextListener>()

    interface WorkspaceContextListener : EventListener {
        fun onContextChanged(context: WorkspaceContext)
    }

    companion object {
        const val MAX_TRACKED_FILES = 10
        const val MAX_SELECTION_LENGTH = 16_384
        val TOPIC: Topic<WorkspaceContextListener> =
            Topic.create("Cliq Workspace Context", WorkspaceContextListener::class.java)
    }

    init {
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (promote(file)) scheduleNotify()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (forget(file)) scheduleNotify()
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let { if (promote(it)) scheduleNotify() }
            }
        })

        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val file = event.editor.virtualFile ?: return
                val active = files.firstOrNull { it.isActive } ?: return
                if (active.path != file.path) return
                val pos = event.newPosition
                active.cursor = CursorPosition(pos.line + 1, pos.column)
                scheduleNotify()
            }
        }, this)

        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) {
                val file = event.editor.virtualFile ?: return
                val active = files.firstOrNull { it.isActive } ?: return
                if (active.path != file.path) return
                active.selectedText = event.editor.selectionModel.selectedText?.let(::truncate)
                scheduleNotify()
            }
        }, this)

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var changed = false
                for (event in events) {
                    when (event) {
                        is VFileDeleteEvent -> {
                            if (files.removeIf { it.path == event.file.path }) changed = true
                        }
                        is VFilePropertyChangeEvent -> {
                            if (event.propertyName != VirtualFile.PROP_NAME) continue
                            val parent = event.file.parent?.path ?: continue
                            val oldName = event.oldValue as? String ?: continue
                            val oldPath = "$parent/$oldName"
                            files.firstOrNull { it.path == oldPath }?.let {
                                it.path = event.file.path
                                it.openedAt = System.currentTimeMillis()
                                changed = true
                            }
                        }
                        else -> {}
                    }
                }
                if (changed) scheduleNotify()
            }
        })

        FileEditorManager.getInstance(project).selectedFiles.forEach { promote(it) }
        if (files.isNotEmpty()) scheduleNotify()
    }

    fun snapshot(): WorkspaceContext = WorkspaceContext(
        openFiles = files.map { it.toImmutable() },
        isTrusted = project.isTrusted(),
    )

    fun addListener(listener: WorkspaceContextListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WorkspaceContextListener) {
        listeners.remove(listener)
    }

    private fun promote(file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem) return false

        files.firstOrNull { it.isActive }?.apply {
            isActive = false
            cursor = null
            selectedText = null
        }

        val existing = files.indexOfFirst { it.path == file.path }
        if (existing != -1) files.removeAt(existing)

        files.add(0, MutableTrackedFile(file.path, System.currentTimeMillis(), isActive = true))
        if (files.size > MAX_TRACKED_FILES) files.removeAt(files.lastIndex)

        seedActiveContextFromEditor(file)
        return true
    }

    private fun forget(file: VirtualFile): Boolean = files.removeIf { it.path == file.path }

    private fun seedActiveContextFromEditor(file: VirtualFile) {
        val active = files.firstOrNull { it.path == file.path && it.isActive } ?: return
        val editor = (FileEditorManager.getInstance(project).getEditors(file)
            .firstOrNull() as? TextEditor)?.editor ?: return
        
        ApplicationManager.getApplication().runReadAction {
            val caret = editor.caretModel.currentCaret
            active.cursor = CursorPosition(caret.logicalPosition.line + 1, caret.logicalPosition.column)
            active.selectedText = editor.selectionModel.selectedText?.let(::truncate)
        }
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_SELECTION_LENGTH) return text
        var cut = MAX_SELECTION_LENGTH
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        return text.take(cut) + "… [truncated]"
    }

    private fun scheduleNotify() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            val snapshot = snapshot()
            listeners.forEach { runCatching { it.onContextChanged(snapshot) }.onFailure { log.warn(it) } }
            project.messageBus.syncPublisher(TOPIC).onContextChanged(snapshot)
        }, 50)
    }

    override fun dispose() {
        listeners.clear()
        files.clear()
    }

    private class MutableTrackedFile(
        var path: String,
        var openedAt: Long,
        var isActive: Boolean,
        var cursor: CursorPosition? = null,
        var selectedText: String? = null,
    ) {
        fun toImmutable() = TrackedFile(path, openedAt, isActive, cursor, selectedText)
    }
}
