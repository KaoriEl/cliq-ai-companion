package com.cliq.plugin.toolwindow

import com.cliq.plugin.diff.CliqDiffManager
import com.cliq.plugin.history.CliqPromptHistory
import com.cliq.plugin.history.PromptHistoryEntry
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.templates.CliqPromptTemplates
import com.cliq.plugin.templates.PromptTemplate
import com.cliq.plugin.templates.PromptTemplateEditDialog
import com.cliq.plugin.templates.PromptTemplateManagerDialog
import com.cliq.plugin.templates.PromptTemplateVariables
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.ui.CliqButton
import com.cliq.plugin.ui.CliqTheme
import com.cliq.plugin.util.CliPathEscaper
import com.cliq.plugin.util.PathUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class CliqToolWindowPanel(private val project: Project) : JBPanel<CliqToolWindowPanel>(BorderLayout()), Disposable, DataProvider {

    interface InsertPromptTextListener : java.util.EventListener {
        fun onInsertPromptTextRequested(text: String)
    }

    companion object {
        val INSERT_PROMPT_TOPIC: com.intellij.util.messages.Topic<InsertPromptTextListener> =
            com.intellij.util.messages.Topic.create("Cliq Insert Prompt Text", InsertPromptTextListener::class.java)
    }

    private val recentList: JBList<VirtualFile>
    private val pinnedList: JBList<VirtualFile>

    override fun dispose() {
    }

    override fun getData(dataId: String): Any? {
        if (CommonDataKeys.VIRTUAL_FILE_ARRAY.`is`(dataId)) {
            val selected = mutableListOf<VirtualFile>()
            selected.addAll(recentList.selectedValuesList)
            selected.addAll(pinnedList.selectedValuesList)
            val result = if (selected.isNotEmpty()) selected.toTypedArray() else null
            return result
        }
        if (CommonDataKeys.VIRTUAL_FILE.`is`(dataId)) {
            return pinnedList.selectedValue ?: recentList.selectedValue
        }
        return null
    }

    init {
        border = JBUI.Borders.empty(12)

        val launcher = project.service<CliqTerminalLauncher>()
        val basePath = project.basePath

        val recentFilesModel = CollectionListModel<VirtualFile>()
        val pinnedFilesModel = CollectionListModel<VirtualFile>()

        recentList = JBList(recentFilesModel)
        pinnedList = JBList(pinnedFilesModel)

        ToolTipManager.sharedInstance().registerComponent(recentList)
        ToolTipManager.sharedInstance().registerComponent(pinnedList)

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val file = event.newFile ?: return
                if (recentFilesModel.items.none { it.path == file.path }) {
                    recentFilesModel.add(0, file)
                    if (recentFilesModel.size > 20) {
                        recentFilesModel.remove(20)
                    }
                }
            }
        })

        fun sendAllToTerminal(model: CollectionListModel<VirtualFile>) {
            val tokens = model.items.mapNotNull { PathUtil.toRelativePosix(basePath, it.path) }
            if (tokens.isEmpty()) return
            val payload = tokens.joinToString(" ") { "@${CliPathEscaper.escape(it)}" } + " "
            TerminalTyper.typeInActiveTerminal(project, " $payload")
        }

        fun buildSection(title: String, list: JBList<VirtualFile>, isPinned: Boolean): JPanel {
            val model = list.model as CollectionListModel<VirtualFile>
            list.apply {
                cellRenderer = CliqFileCellRenderer(basePath)
                emptyText.text = if (isPinned) "Drag files here" else "No recent files"
                background = CliqTheme.SURFACE

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        val index = locationToIndex(e.point)
                        if (index == -1) return
                        val rect = getCellBounds(index, index)
                        val file = model.getElementAt(index)

                        when {
                            e.x > rect.width - 25 -> model.remove(file)
                            e.x > rect.width - 50 -> {
                                val rel = PathUtil.toRelativePosix(basePath, file.path) ?: return
                                TerminalTyper.typeInActiveTerminal(project, " @${CliPathEscaper.escape(rel)} ")
                            }
                        }
                    }
                })
            }

            val scroll = com.intellij.ui.components.JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
            }

            val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }

            headerActions.add(JLabel(AllIcons.Actions.MoveTo2).apply {
                toolTipText = "Send all to terminal"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = sendAllToTerminal(model)
                })
            })

            if (isPinned) {
                headerActions.add(JLabel(AllIcons.General.Add).apply {
                    toolTipText = "Add files"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent) = chooseAndAddFiles(model)
                    })
                })
            }

            headerActions.add(JLabel(AllIcons.Actions.GC).apply {
                toolTipText = if (isPinned) "Clear context" else "Clear recent files"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (model.size > 0) model.removeAll()
                    }
                })
            })

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(6)
                val titleLabel = JBLabel(title).apply {
                    font = CliqTheme.captionFont(font).deriveFont(Font.BOLD)
                }
                add(titleLabel, BorderLayout.WEST)
                add(headerActions, BorderLayout.EAST)
            }

            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
                add(header, BorderLayout.NORTH)
                add(scroll, BorderLayout.CENTER)
            }
        }

        val toolbar = buildTopToolbar(launcher)

        val dropZone = FilesDropZone { dropped ->
            dropped.forEach { if (pinnedFilesModel.items.none { p -> p.path == it.path }) pinnedFilesModel.add(it) }
        }

        val filesSplitter = com.intellij.ui.JBSplitter(false, 0.5f).apply {
            firstComponent = buildSection("Recently opened", recentList, false)
            secondComponent = buildSection("Context", pinnedList, true)
        }

        dropZone.add(filesSplitter, BorderLayout.CENTER)

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(dropZone, BorderLayout.CENTER)
            add(buildPendingChangesSection(basePath), BorderLayout.SOUTH)
        }

        val mainSplitter = com.intellij.ui.JBSplitter(true, 0.6f).apply {
            setHonorComponentsMinimumSize(true)
            firstComponent = topPanel

            secondComponent = buildPromptPanel(
                pinnedFilesModel,
                recentFilesModel,
                basePath,
                { FileEditorManager.getInstance(project).selectedFiles.firstOrNull() }
            )
        }

        add(toolbar, BorderLayout.NORTH)
        add(mainSplitter, BorderLayout.CENTER)
    }

    private fun buildPendingChangesSection(basePath: String?): JPanel {
        val diffManager = project.service<CliqDiffManager>()
        val model = CollectionListModel<String>()

        val list = JBList(model).apply {
            cellRenderer = CliqPendingChangeCellRenderer(basePath)
            background = CliqTheme.SURFACE
            emptyText.text = "No pending changes"

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index == -1) return
                    val rect = getCellBounds(index, index)
                    val filePath = model.getElementAt(index)

                    when {
                        e.x > rect.width - 25 -> diffManager.reject(filePath)
                        e.x > rect.width - 50 -> diffManager.accept(filePath)
                        else -> diffManager.focusDiff(filePath)
                    }
                }
            })
        }
        ToolTipManager.sharedInstance().registerComponent(list)

        val scroll = com.intellij.ui.components.JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }

        val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(JLabel(AllIcons.Actions.Checked).apply {
                toolTipText = "Accept all pending changes"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = diffManager.acceptAll()
                })
            })
            add(JLabel(AllIcons.Actions.Cancel).apply {
                toolTipText = "Reject all pending changes"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = diffManager.rejectAll()
                })
            })
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            val titleLabel = JBLabel("Pending Changes").apply {
                font = CliqTheme.captionFont(font).deriveFont(Font.BOLD)
            }
            add(titleLabel, BorderLayout.WEST)
            add(headerActions, BorderLayout.EAST)
        }

        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8, 0, 8)
            isVisible = false
            preferredSize = Dimension(0, 140)
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }

        fun refresh(paths: List<String>) {
            model.removeAll()
            paths.forEach { model.add(it) }
            container.isVisible = paths.isNotEmpty()
            container.revalidate()
            container.repaint()
        }

        refresh(diffManager.pendingFilePaths())

        project.messageBus.connect(this).subscribe(CliqDiffManager.PENDING_TOPIC, object : CliqDiffManager.PendingReviewsListener {
            override fun onPendingReviewsChanged(pendingFilePaths: List<String>) {
                ApplicationManager.getApplication().invokeLater { refresh(pendingFilePaths) }
            }
        })

        return container
    }

    private fun chooseAndAddFiles(model: CollectionListModel<VirtualFile>) {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withTitle("Select Files for Context")
        val chosen = FileChooser.chooseFiles(descriptor, project, null)
        chosen.forEach { vf ->
            if (model.items.none { it.path == vf.path }) {
                model.add(vf)
            }
        }
    }

    private fun insertSingleFileToTerminal(file: VirtualFile) {
        val relPath = PathUtil.toRelativePosix(project.basePath, file.path) ?: return
        val payload = " @${CliPathEscaper.escape(relPath)} "
        TerminalTyper.typeInActiveTerminal(project, payload)
    }

    private fun buildTopToolbar(launcher: CliqTerminalLauncher): JPanel {
        val comboModel = DefaultComboBoxModel(CliqSettings.getInstance().agents().toTypedArray())

        val agentCombo = com.intellij.openapi.ui.ComboBox(comboModel).apply {
            isOpaque = false
            renderer = SimpleListCellRenderer.create("") { it?.displayName }
        }

        project.messageBus.connect(this).subscribe(CliqSettings.TOPIC, object : CliqSettings.AgentsListener {
            override fun onAgentsChanged(agents: List<com.cliq.plugin.agents.CliAgentDefinition>) {
                ApplicationManager.getApplication().invokeLater {
                    val previouslySelectedId = (agentCombo.selectedItem as? com.cliq.plugin.agents.CliAgentDefinition)?.id
                    comboModel.removeAllElements()
                    agents.forEach { comboModel.addElement(it) }
                    val restored = agents.firstOrNull { it.id == previouslySelectedId }
                    comboModel.selectedItem = restored ?: agents.firstOrNull()
                }
            }
        })

        val startBtn = CliqButton("Start", CliqButton.Variant.GHOST).apply {
            addActionListener {
                val selectedAgent = agentCombo.selectedItem as? com.cliq.plugin.agents.CliAgentDefinition
                    ?: return@addActionListener
                launcher.launch(selectedAgent)
            }
        }

        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(12)

            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insetsRight(8)
            }

            add(agentCombo, gbc)

            gbc.weightx = 0.0
            gbc.insets = JBUI.emptyInsets()
            add(startBtn, gbc)
        }
    }

    private fun buildPromptPanel(
        pinnedModel: CollectionListModel<VirtualFile>,
        recentModel: CollectionListModel<VirtualFile>,
        basePath: String?,
        getActiveFile: () -> VirtualFile?
    ): JPanel {
        val container = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.emptyTop(8)
            isOpaque = false
        }

        val cbActive = com.intellij.ui.components.JBCheckBox("Active file", false).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }
        val cbContext = com.intellij.ui.components.JBCheckBox("Context", false).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }
        val cbRecent = com.intellij.ui.components.JBCheckBox("Recently opened", false).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }

        val checksPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            add(cbActive)
            add(cbContext)
            add(cbRecent)
        }

        val promptArea = com.intellij.ui.components.JBTextArea().apply {
            emptyText.text = "Ask Cliq... (Enter to send, Shift+Enter for new line)"
            lineWrap = true
            wrapStyleWord = true
            rows = 3

            isOpaque = false
            background = null

            foreground = CliqTheme.PRIMARY_TEXT
            font = CliqTheme.captionFont(font)
            border = JBUI.Borders.empty(8, 12)
        }

        project.messageBus.connect(this).subscribe(INSERT_PROMPT_TOPIC, object : InsertPromptTextListener {
            override fun onInsertPromptTextRequested(text: String) {
                ApplicationManager.getApplication().invokeLater {
                    promptArea.text = text
                    promptArea.requestFocusInWindow()
                }
            }
        })

        val inputWrapper = JPanel(BorderLayout()).apply {
            background = CliqTheme.INPUT_BG
            isOpaque = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CliqTheme.SURFACE_BORDER, 1, true),
                JBUI.Borders.empty(2)
            )

            val scrollPane = com.intellij.ui.components.JBScrollPane(promptArea).apply {
                border = BorderFactory.createEmptyBorder()
                isOpaque = false
                viewport.isOpaque = false
                viewport.background = null
            }

            val historyIcon = JLabel(AllIcons.Vcs.History).apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "Insert a previously sent prompt"
                border = JBUI.Borders.empty(0, 4)
            }

            val templateIcon = JLabel(AllIcons.Actions.ListFiles).apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "Insert prompt template"
                border = JBUI.Borders.empty(0, 4)
            }

            val saveTemplateIcon = JLabel(AllIcons.Actions.MenuSaveall).apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "Save current input as prompt template"
                border = JBUI.Borders.empty(0, 4)
            }

            val sendIcon = JLabel(AllIcons.Actions.Execute).apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "Send prompt"
                border = JBUI.Borders.empty(0, 4, 8, 12)
            }

            val actionIconsRow = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(historyIcon)
                add(templateIcon)
                add(saveTemplateIcon)
                add(sendIcon)
            }

            val iconPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                background = null
                add(actionIconsRow, BorderLayout.SOUTH)
            }

            add(scrollPane, BorderLayout.CENTER)
            add(iconPanel, BorderLayout.EAST)

            fun insertTextIntoPrompt(text: String) {
                if (promptArea.selectedText != null) {
                    promptArea.replaceSelection(text)
                } else {
                    promptArea.document.insertString(promptArea.caretPosition, text, null)
                }
                ApplicationManager.getApplication().invokeLater { promptArea.requestFocusInWindow() }
            }

            fun insertTemplate(template: PromptTemplate) {
                val activeVf = getActiveFile()
                val activeFileValue = activeVf?.let { PathUtil.toRelativePosix(basePath, it.path) }
                val selectionValue = FileEditorManager.getInstance(project).selectedTextEditor?.selectionModel?.selectedText
                val clipboardValue = try {
                    CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
                } catch (t: Throwable) {
                    null
                }

                val resolvedContent = PromptTemplateVariables.resolve(
                    template.content,
                    activeFileValue,
                    selectionValue,
                    clipboardValue,
                )
                insertTextIntoPrompt(resolvedContent)
            }

            fun insertHistoryEntry(entry: PromptHistoryEntry) = insertTextIntoPrompt(entry.text)

            fun showHistoryPopup() {
                val history = project.service<CliqPromptHistory>().entries()
                val group = DefaultActionGroup()

                if (history.isEmpty()) {
                    group.add(object : AnAction("No Sent Prompts Yet") {
                        override fun actionPerformed(e: AnActionEvent) = Unit
                    }.apply { templatePresentation.isEnabled = false })
                } else {
                    history.forEach { entry ->
                        val firstLine = entry.text.lineSequence().first()
                        val label = if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
                        group.add(object : AnAction(label) {
                            override fun actionPerformed(e: AnActionEvent) = insertHistoryEntry(entry)
                        })
                    }
                    group.addSeparator()
                    group.add(object : AnAction("Clear History", null, AllIcons.Actions.GC) {
                        override fun actionPerformed(e: AnActionEvent) {
                            project.service<CliqPromptHistory>().clear()
                        }
                    })
                }

                JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Prompt History",
                        group,
                        DataManager.getInstance().getDataContext(historyIcon),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true,
                    )
                    .showUnderneathOf(historyIcon)
            }

            historyIcon.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = showHistoryPopup()
            })

            fun showTemplatePopup() {
                val templates = CliqPromptTemplates.getInstance().templates()
                val group = DefaultActionGroup()

                if (templates.isEmpty()) {
                    group.add(object : AnAction("No Templates Yet") {
                        override fun actionPerformed(e: AnActionEvent) = Unit
                    }.apply { templatePresentation.isEnabled = false })
                } else {
                    templates.forEach { template ->
                        group.add(object : AnAction(template.title, template.description.ifBlank { null }, null) {
                            override fun actionPerformed(e: AnActionEvent) = insertTemplate(template)
                        })
                    }
                }

                group.addSeparator()
                group.add(object : AnAction("Manage Templates…", null, AllIcons.General.Settings) {
                    override fun actionPerformed(e: AnActionEvent) {
                        PromptTemplateManagerDialog().show()
                    }
                })

                JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Insert Prompt Template",
                        group,
                        DataManager.getInstance().getDataContext(templateIcon),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true,
                    )
                    .showUnderneathOf(templateIcon)
            }

            fun saveCurrentInputAsTemplate() {
                val text = promptArea.text
                if (text.isBlank()) return
                val existingTitles = CliqPromptTemplates.getInstance().templates().map { it.title }.toSet()
                val dialog = PromptTemplateEditDialog(PromptTemplate(content = text), existingTitles)
                if (dialog.showAndGet()) {
                    CliqPromptTemplates.getInstance().addTemplate(dialog.buildResult())
                }
            }

            templateIcon.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = showTemplatePopup()
            })

            saveTemplateIcon.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = saveCurrentInputAsTemplate()
            })

            fun sendPrompt() {
                val userText = promptArea.text.trim()
                if (userText.isEmpty()) return

                val tokensToAttach = mutableSetOf<String>()

                val activeVf = getActiveFile()
                if (cbActive.isSelected && activeVf != null) {
                    PathUtil.toRelativePosix(basePath, activeVf.path)?.let { tokensToAttach.add(it) }
                }

                if (cbContext.isSelected) {
                    pinnedModel.items.forEach { vf ->
                        PathUtil.toRelativePosix(basePath, vf.path)?.let { tokensToAttach.add(it) }
                    }
                }

                if (cbRecent.isSelected) {
                    recentModel.items.forEach { vf ->
                        PathUtil.toRelativePosix(basePath, vf.path)?.let { tokensToAttach.add(it) }
                    }
                }

                val contextString = if (tokensToAttach.isNotEmpty()) {
                    tokensToAttach.joinToString(" ") { "@${CliPathEscaper.escape(it)}" }
                } else ""

                val payload = buildString {
                    if (contextString.isNotEmpty()) {
                        append(contextString)
                        append(" \n\n")
                    }
                    append(userText)
                }

                project.service<CliqPromptHistory>().record(userText)
                TerminalTyper.typeInActiveTerminal(project, payload, execute = true)
                promptArea.text = ""
            }

            sendIcon.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = sendPrompt()
            })

            promptArea.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode != java.awt.event.KeyEvent.VK_ENTER) return
                    if (e.isShiftDown) {
                        e.consume()
                        val caretPos = promptArea.caretPosition
                        promptArea.document.insertString(caretPos, "\n", null)
                        return
                    }
                    e.consume()
                    sendPrompt()
                }
            })
        }

        container.add(checksPanel, BorderLayout.NORTH)
        container.add(inputWrapper, BorderLayout.CENTER)

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
}
