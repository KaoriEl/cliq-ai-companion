package com.cliq.plugin.startup

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.mcp.CliqIdeServer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Modern replacement for the deprecated `StartupActivity` interface – runs
 * once per opened project on a background coroutine. We use it to show a
 * one-time welcome balloon so the user knows where to find the new tool window.
 */
class CliqStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Boot the local MCP server so CLI agents launched from the integrated
        // terminal can connect back to the IDE for diff reviews and workspace
        // context. Eager-fetch the open-files tracker so it begins listening
        // before any tabs are opened.
        project.service<CliqIdeServer>().start()
        project.service<com.cliq.plugin.context.OpenFilesTracker>()

        val welcomeState = ApplicationManager.getApplication().getService(CliqWelcomeState::class.java)
        if (welcomeState.welcomeShown) return

        // Notifications must be posted from the EDT.
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
                .createNotification(
                    "Cliq AI Companion installed",
                    "Open the Cliq tool window or press Ctrl+Alt+Q to launch a configured CLI agent.",
                    NotificationType.INFORMATION,
                )
                .notify(project)
            welcomeState.welcomeShown = true
        }
    }
}

/**
 * One-bit persisted state that remembers whether the welcome notification has
 * already been shown. Stored in the IDE config dir so re-opening a project
 * does not spam the user.
 */
@Service(Service.Level.APP)
@State(name = "CliqWelcomeState", storages = [Storage("cliq-welcome.xml")])
class CliqWelcomeState : PersistentStateComponent<CliqWelcomeState.Snapshot> {

    data class Snapshot(var welcomeShown: Boolean = false)

    private var snapshot = Snapshot()

    var welcomeShown: Boolean
        get() = snapshot.welcomeShown
        set(value) { snapshot.welcomeShown = value }

    override fun getState(): Snapshot = snapshot

    override fun loadState(state: Snapshot) {
        snapshot = state
    }
}
