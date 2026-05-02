package com.cliq.plugin.toolwindow

import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.ui.CliqButton
import com.cliq.plugin.ui.CliqTheme
import com.cliq.plugin.util.CliPathEscaper
import com.cliq.plugin.util.PathUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Default content of the Cliq tool window.
 *
 * Provides a one-click launcher for each configured agent, a Files Context
 * panel (drop zone + list + branded buttons) for building @-references to
 * inject into the active terminal, and a shortcut to the settings page.
 */
class CliqToolWindowPanel(private val project: Project) : JBPanel<CliqToolWindowPanel>(BorderLayout()), Disposable {

    override fun dispose() {
    }

    init {
        // Добавляем глобальный отступ! Он выровняет ВСЕ компоненты по ширине.
        border = JBUI.Borders.empty(12)

        val launcher = project.service<CliqTerminalLauncher>()
        val basePath = project.basePath

        val recentFilesModel = CollectionListModel<VirtualFile>()
        val pinnedFilesModel = CollectionListModel<VirtualFile>()

        // --- ЛОГИКА RECENT FILES (Авто-замена старых) ---
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val file = event.newFile ?: return
                if (recentFilesModel.items.none { it.path == file.path }) {
                    recentFilesModel.add(0, file)
                    // Пятый пункт: держим последние 20, удаляем старые
                    if (recentFilesModel.size > 20) {
                        recentFilesModel.remove(20)
                    }
                }
            }
        })

        // Функция вставки нескольких файлов (для кнопок "Send All")
        fun sendAllToTerminal(model: CollectionListModel<VirtualFile>) {
            val tokens = model.items.mapNotNull { PathUtil.toRelativePosix(basePath, it.path) }
            if (tokens.isEmpty()) return
            val payload = tokens.joinToString(" ") { "@${CliPathEscaper.escape(it)}" } + " "
            TerminalTyper.typeInActiveTerminal(project, " $payload")
        }

        // Вспомогательная функция сборки секции
        fun buildSection(title: String, model: CollectionListModel<VirtualFile>, isPinned: Boolean): JPanel {
            val list = JBList(model).apply {
                cellRenderer = CliqFileCellRenderer(basePath, isPinned)
                emptyText.text = if (isPinned) "Drag files here" else "No recent files"
                background = CliqTheme.SURFACE

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        val index = locationToIndex(e.point)
                        if (index == -1) return
                        val rect = getCellBounds(index, index)
                        val file = model.getElementAt(index)

                        if (e.x > rect.width - 25 && isPinned) {
                            model.remove(file)
                        } else if (e.x > rect.width - (if (isPinned) 50 else 25)) {
                            val rel = PathUtil.toRelativePosix(basePath, file.path) ?: return
                            TerminalTyper.typeInActiveTerminal(project, " @${CliPathEscaper.escape(rel)} ")
                        }
                    }
                })
            }

            val scroll = com.intellij.ui.components.JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
            }

            // Кастомный заголовок с кнопками действий
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

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(6)
                // Заголовок нормальным регистром и жирным шрифтом!
                val titleLabel = JBLabel(title).apply {
                    font = CliqTheme.captionFont(font).deriveFont(Font.BOLD)
                }
                add(titleLabel, BorderLayout.WEST)
                add(headerActions, BorderLayout.EAST)
            }

            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8) // Увеличенные отступы
                add(header, BorderLayout.NORTH)
                add(scroll, BorderLayout.CENTER)
            }
        }

        // --- SMART CONTEXT END ---

        val toolbar = buildTopToolbar(launcher)

        // --- СБОРКА ИНТЕРФЕЙСА ---
        val dropZone = FilesDropZone { dropped ->
            dropped.forEach { if (pinnedFilesModel.items.none { p -> p.path == it.path }) pinnedFilesModel.add(it) }
        }

        // false означает горизонтальное разделение (Колонки слева и справа)
        val filesSplitter = com.intellij.ui.JBSplitter(false, 0.5f).apply {
            firstComponent = buildSection("Recently opened", recentFilesModel, false)
            secondComponent = buildSection("Context", pinnedFilesModel, true) // Переименовали в "Context"
        }

        dropZone.add(filesSplitter, BorderLayout.CENTER)

        val mainSplitter = com.intellij.ui.JBSplitter(true, 0.6f).apply {
            setHonorComponentsMinimumSize(true)
            firstComponent = dropZone

            // Вот здесь передаем новые аргументы!
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
        // 1. Получаем актуальный список агентов из настроек
        val agents = CliqSettings.getInstance().agents()

        // 2. Создаем дропдаун, используя displayName каждого агента
        val agentCombo = com.intellij.openapi.ui.ComboBox(
            agents.map { it.displayName }.toTypedArray()
        ).apply {
            isOpaque = false
        }

        // 3. Кнопка Start берет выбранного агента по индексу из списка
        val startBtn = CliqButton("Start", CliqButton.Variant.GHOST).apply {
            addActionListener {
                val selectedAgent = agents.getOrNull(agentCombo.selectedIndex)
                if (selectedAgent != null) {
                    launcher.launch(selectedAgent)
                }
            }
        }

        // Используем GridBagLayout для контроля ширины
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            // УБИРАЕМ border = CliqTheme.PANEL_PADDING
            // Оставляем только отступ снизу, чтобы отделить от DropZone
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
            // УБИРАЕМ border = CliqTheme.PANEL_PADDING
            // Добавляем небольшой отступ сверху, чтобы отделить от сплиттера
            border = JBUI.Borders.emptyTop(8)
            isOpaque = false
        }

        // Кастомная стилизация чекбоксов
        val cbActive = com.intellij.ui.components.JBCheckBox("Active file", true).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }
        val cbContext = com.intellij.ui.components.JBCheckBox("Context", true).apply {
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

            // ДЕЛАЕМ ПОЛНОСТЬЮ ПРОЗРАЧНЫМ
            isOpaque = false
            background = null

            foreground = CliqTheme.PRIMARY_TEXT
            font = CliqTheme.captionFont(font)
            border = JBUI.Borders.empty(8, 12) // Отступы текста внутри инпута
        }

        val inputWrapper = JPanel(BorderLayout()).apply {
            background = CliqTheme.INPUT_BG // Единый фон задается только здесь
            isOpaque = true // Отрисовываем фон обертки
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CliqTheme.SURFACE_BORDER, 1, true),
                JBUI.Borders.empty(2)
            )

            val scrollPane = com.intellij.ui.components.JBScrollPane(promptArea).apply {
                border = BorderFactory.createEmptyBorder()
                isOpaque = false
                viewport.isOpaque = false // ВАЖНО: прозрачный вьюпорт скролла
                viewport.background = null
            }

            val sendIcon = JLabel(AllIcons.Actions.Execute).apply {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "Send prompt"
                border = JBUI.Borders.empty(0, 4, 8, 12) // Аккуратный отступ иконки
            }

            val iconPanel = JPanel(BorderLayout()).apply {
                isOpaque = false // Прозрачный контейнер иконки, просвечивает INPUT_BG
                background = null
                add(sendIcon, BorderLayout.SOUTH)
            }

            add(scrollPane, BorderLayout.CENTER)
            add(iconPanel, BorderLayout.EAST)

            fun sendPrompt() {
                val userText = promptArea.text.trim()
                if (userText.isEmpty()) return

                val tokensToAttach = mutableSetOf<String>()

                // 1. Добавляем активный файл, если стоит галочка
                val activeVf = getActiveFile()
                if (cbActive.isSelected && activeVf != null) {
                    PathUtil.toRelativePosix(basePath, activeVf.path)?.let { tokensToAttach.add(it) }
                }

                // 2. Добавляем файлы из Pinned Context, если стоит галочка
                if (cbContext.isSelected) {
                    pinnedModel.items.forEach { vf ->
                        PathUtil.toRelativePosix(basePath, vf.path)?.let { tokensToAttach.add(it) }
                    }
                }

                // 3. Добавляем файлы из Recently Opened, если стоит галочка
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

                TerminalTyper.typeInActiveTerminal(project, payload, execute = true)
                promptArea.text = ""
            }

            sendIcon.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = sendPrompt()
            })

            promptArea.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && !e.isShiftDown) {
                        e.consume()
                        sendPrompt()
                    }
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
