package com.cliq.plugin.toolwindow

import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.ui.CliqButton
import com.cliq.plugin.ui.CliqTheme
import com.cliq.plugin.util.CliPathEscaper
import com.cliq.plugin.util.PathUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Default content of the Cliq tool window.
 *
 * Provides a one-click launcher for each configured agent, a Files Context
 * panel (drop zone + list + branded buttons) for building @-references to
 * inject into the active terminal, and a shortcut to the settings page.
 */
class CliqToolWindowPanel(private val project: Project) : JBPanel<CliqToolWindowPanel>(BorderLayout()) {

    init {
        val settings = CliqSettings.getInstance()
        val launcher = project.service<CliqTerminalLauncher>()
        val basePath = project.basePath

        // ── Files Context state ──────────────────────────────────────────────
        val pickedFilesModel = CollectionListModel<VirtualFile>()
        val pickedFilesList = JBList(pickedFilesModel).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean,
                ): java.awt.Component {
                    val c = super.getListCellRendererComponent(
                        list, displayPath(value as? VirtualFile, basePath),
                        index, isSelected, cellHasFocus,
                    )
                    border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                    if (isSelected) {
                        background = CliqTheme.PRIMARY
                        foreground = CliqTheme.ON_PRIMARY
                    }
                    return c
                }
            }
        }

        pickedFilesList.dropTarget = java.awt.dnd.DropTarget(
            pickedFilesList, java.awt.dnd.DnDConstants.ACTION_COPY,
            object : java.awt.dnd.DropTargetAdapter() {
                override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                    dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                    try {
                        if (dtde.transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = dtde.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                            files.forEach { f ->
                                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                    .refreshAndFindFileByIoFile(f)
                                    ?.let { vf ->
                                        if (pickedFilesModel.items.none { it.path == vf.path }) pickedFilesModel.add(vf)
                                    }
                            }
                            dtde.dropComplete(true)
                        } else {
                            dtde.dropComplete(false)
                        }
                    } catch (_: Throwable) {
                        dtde.dropComplete(false)
                    }
                }
            },
            true,
        )

        fun chooseAndAdd() {
            val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                .withTitle("Select Files for Context")
            val chosen = FileChooser.chooseFiles(descriptor, project, null)
            chosen.forEach { vf ->
                if (pickedFilesModel.items.none { it.path == vf.path }) {
                    pickedFilesModel.add(vf)
                }
            }
        }

        fun insertIntoTerminal() {
            val items: List<VirtualFile> = pickedFilesList.selectedValuesList
                .ifEmpty { pickedFilesModel.items }
            if (items.isEmpty()) return
            val tokens = items
                .mapNotNull { vf -> PathUtil.toRelativePosix(basePath, vf.path) }
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return
            val payload = tokens.joinToString(" ") { "@${CliPathEscaper.escape(it)}" } + " "
            TerminalTyper.typeInActiveTerminal(project, payload)
        }
        // ────────────────────────────────────────────────────────────────────

        val toolbar = buildTopToolbar(launcher, ::chooseAndAdd)
        val filesPanel = buildFilesContextPanel(pickedFilesModel, pickedFilesList, ::insertIntoTerminal)

        add(toolbar, BorderLayout.NORTH)
        add(filesPanel, BorderLayout.CENTER)
    }

    private fun buildTopToolbar(
        launcher: CliqTerminalLauncher,
        onAddFiles: () -> Unit,
    ): javax.swing.JComponent {
        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            isOpaque = false
        }

        // Primary action — Add files (используется чаще всего)
        val addBtn = CliqButton("+ Add files", CliqButton.Variant.GHOST).apply {
            toolTipText = "Add files to context (you can also drag-drop)"
            addActionListener { onAddFiles() }
        }
        bar.add(addBtn)
        bar.add(Box.createHorizontalStrut(6))

        // Vertical separator
        val separator = JPanel().apply {
            preferredSize = Dimension(1, 16)
            maximumSize = Dimension(1, 16)
            background = CliqTheme.SURFACE_BORDER
        }
        bar.add(separator)
        bar.add(Box.createHorizontalStrut(6))

        // Кнопки агентов — компактные, рядом
        CliqSettings.getInstance().agents().forEach { agent ->
            val btn = CliqButton(agent.displayName, CliqButton.Variant.GHOST).apply {
                toolTipText = "Run ${agent.displayName}: ${agent.command}"
                addActionListener { launcher.launch(agent) }
            }
            bar.add(btn)
            bar.add(Box.createHorizontalStrut(4))
        }

        bar.add(Box.createHorizontalGlue())
        return bar
    }

    private fun buildFilesContextPanel(
        model: CollectionListModel<VirtualFile>,
        list: JBList<VirtualFile>,
        onInsertClick: () -> Unit,
    ): JPanel {
        val container = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            isOpaque = false
        }

        // Header: title + counter
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 2, 6, 2)
        }
        val title = JBLabel("Context Files").apply {
            font = CliqTheme.titleFont(font)
        }
        val counter = JBLabel("(0)").apply {
            font = CliqTheme.captionFont(font)
            foreground = CliqTheme.SECONDARY_TEXT
            border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
        }
        header.add(title)
        header.add(counter)
        header.add(Box.createHorizontalGlue())

        // List + empty-state overlay
        val listScroll = JBScrollPane(list).apply {
            border = BorderFactory.createLineBorder(CliqTheme.SURFACE_BORDER, 1, true)
        }

        // Эмпти-стейт: показывается поверх scroll, когда model пуст.
        val emptyState = FilesDropZone { dropped ->
            dropped.forEach { vf ->
                if (model.items.none { it.path == vf.path }) model.add(vf)
            }
        }

        // Stacking-обёртка через CardLayout: переключает list <-> emptyState.
        val cards = JPanel(java.awt.CardLayout()).apply {
            isOpaque = false
            add(emptyState, "EMPTY")
            add(listScroll, "LIST")
        }
        fun refreshState() {
            val cl = cards.layout as java.awt.CardLayout
            cl.show(cards, if (model.isEmpty) "EMPTY" else "LIST")
            counter.text = "(${model.size})"
        }
        model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = refreshState()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = refreshState()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = refreshState()
        })
        refreshState()

        // Footer: primary Insert + secondary text-actions справа
        val insertBtn = CliqButton("Insert into Terminal", CliqButton.Variant.PRIMARY).apply {
            addActionListener { onInsertClick() }
            toolTipText = "Send selected (or all) files as @-tokens to the active terminal"
        }
        val removeLink = makeLinkLabel("Remove selected") {
            list.selectedValuesList.forEach { model.remove(it) }
        }
        val clearLink = makeLinkLabel("Clear all") { model.removeAll() }

        val footer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            add(insertBtn)
            add(Box.createHorizontalGlue())
            add(removeLink)
            add(Box.createHorizontalStrut(12))
            add(clearLink)
        }

        container.add(header, BorderLayout.NORTH)
        container.add(cards, BorderLayout.CENTER)
        container.add(footer, BorderLayout.SOUTH)
        return container
    }

    private fun makeLinkLabel(text: String, onClick: () -> Unit): JBLabel {
        return JBLabel(text).apply {
            font = CliqTheme.captionFont(font)
            foreground = CliqTheme.SECONDARY_TEXT
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    foreground = CliqTheme.PRIMARY
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    foreground = CliqTheme.SECONDARY_TEXT
                }
            })
        }
    }

    private fun displayPath(vf: VirtualFile?, basePath: String?): String {
        if (vf == null) return ""
        return PathUtil.toRelativePosix(basePath, vf.path) ?: vf.path
    }
}
