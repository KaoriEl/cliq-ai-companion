package com.cliq.plugin.settings

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.agents.Agent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persistent settings for Cliq.
 *
 * The state is serialised to `cliq-settings.xml` inside the IDE config directory
 * via the standard `PersistentStateComponent` contract. Two CLI commands are
 * exposed: one for Claude, one for Gemini. Each may be a bare executable name
 * (resolved via `PATH`) or a full filesystem path.
 */
@State(
    name = "CliqSettings",
    storages = [Storage("cliq-settings.xml")],
)
@Service(Service.Level.APP)
class CliqSettings : PersistentStateComponent<CliqSettings> {

    var claudeCommand: String = CliqPlugin.DEFAULT_CLAUDE_COMMAND
    var geminiCommand: String = CliqPlugin.DEFAULT_GEMINI_COMMAND
    var autoApplyChanges: Boolean = false

    override fun getState(): CliqSettings = this

    override fun loadState(state: CliqSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * Returns the configured agents in the order they should be presented to the user.
     * The list is rebuilt on every call so it always reflects the latest settings.
     */
    fun agents(): List<Agent> = listOf(
        Agent(Agent.CLAUDE_ID, "Claude", claudeCommand.ifBlank { CliqPlugin.DEFAULT_CLAUDE_COMMAND }),
        Agent(Agent.GEMINI_ID, "Gemini", geminiCommand.ifBlank { CliqPlugin.DEFAULT_GEMINI_COMMAND }),
    )

    companion object {
        @JvmStatic
        fun getInstance(): CliqSettings =
            ApplicationManager.getApplication().getService(CliqSettings::class.java)
    }
}
