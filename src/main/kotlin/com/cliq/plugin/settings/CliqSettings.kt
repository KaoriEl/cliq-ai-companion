package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.util.EventListener

@State(
    name = "CliqSettings",
    storages = [Storage("cliq-settings.xml")],
)
@Service(Service.Level.APP)
class CliqSettings : PersistentStateComponent<CliqSettings.State> {

    class State {
        var agents: MutableList<CliAgentDefinition> = mutableListOf()
        var autoApplyChanges: Boolean = false
    }

    interface AgentsListener : EventListener {
        fun onAgentsChanged(agents: List<CliAgentDefinition>)
    }

    companion object {
        val TOPIC: Topic<AgentsListener> = Topic.create("Cliq Agents Changed", AgentsListener::class.java)

        @JvmStatic
        fun getInstance(): CliqSettings =
            ApplicationManager.getApplication().getService(CliqSettings::class.java)

        fun defaultAgents(): MutableList<CliAgentDefinition> = mutableListOf(
            CliAgentDefinition(
                id = CliAgentDefinition.CLAUDE_ID,
                displayName = "Claude",
                executablePath = "claude",
            ),
            CliAgentDefinition(
                id = CliAgentDefinition.GEMINI_ID,
                displayName = "Gemini",
                executablePath = "gemini",
            ),
            CliAgentDefinition(
                id = CliAgentDefinition.QWEN_ID,
                displayName = "Qwen",
                executablePath = "qwen",
            ),
        )
    }

    private var myState = State()

    var autoApplyChanges: Boolean
        get() = myState.autoApplyChanges
        set(value) {
            myState.autoApplyChanges = value
        }

    fun agents(): List<CliAgentDefinition> = myState.agents.toList()

    fun setAgents(newAgents: List<CliAgentDefinition>) {
        myState.agents = newAgents.map { it.copy(environmentVariables = it.environmentVariables.toMutableMap()) }
            .toMutableList()
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TOPIC)
            .onAgentsChanged(agents())
    }

    fun resetToDefaults() {
        setAgents(defaultAgents())
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        if (myState.agents.isEmpty()) {
            myState.agents = defaultAgents()
        }
    }

    override fun noStateLoaded() {
        myState.agents = defaultAgents()
    }
}
