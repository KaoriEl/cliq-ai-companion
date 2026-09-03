package com.cliq.plugin.templates

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.util.EventListener

@State(
    name = "CliqPromptTemplates",
    storages = [Storage("cliq-prompt-templates.xml")],
)
@Service(Service.Level.APP)
class CliqPromptTemplates : PersistentStateComponent<CliqPromptTemplates.State> {

    class State {
        var templates: MutableList<PromptTemplate> = mutableListOf()
    }

    interface TemplatesListener : EventListener {
        fun onTemplatesChanged(templates: List<PromptTemplate>)
    }

    companion object {
        val TOPIC: Topic<TemplatesListener> = Topic.create("Cliq Prompt Templates Changed", TemplatesListener::class.java)

        @JvmStatic
        fun getInstance(): CliqPromptTemplates =
            ApplicationManager.getApplication().getService(CliqPromptTemplates::class.java)

        fun defaultTemplates(): MutableList<PromptTemplate> = mutableListOf(
            PromptTemplate(
                title = "Explain Code",
                content = "Explain what the following code does, step by step:\n\n{{selection}}",
                description = "Get a plain-language walkthrough of the selected code.",
            ),
            PromptTemplate(
                title = "Refactor & Optimize",
                content = "Refactor the following code for readability and performance. Explain each change you make:\n\n{{selection}}",
                description = "Ask the agent to clean up and speed up the code.",
            ),
            PromptTemplate(
                title = "Write Unit Tests",
                content = "Write comprehensive unit tests for the following code, covering edge cases:\n\n{{selection}}",
                description = "Generate a test suite for the selected code.",
            ),
            PromptTemplate(
                title = "Find Bugs",
                content = "Review the following code for bugs, edge cases, and potential issues:\n\n{{selection}}",
                description = "Ask the agent to audit the code for defects.",
            ),
        )
    }

    private var myState = State()

    fun templates(): List<PromptTemplate> = myState.templates.toList()

    fun setTemplates(newTemplates: List<PromptTemplate>) {
        myState.templates = newTemplates.toMutableList()
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TOPIC)
            .onTemplatesChanged(templates())
    }

    fun addTemplate(template: PromptTemplate) {
        setTemplates(templates() + template)
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        if (myState.templates.isEmpty()) {
            myState.templates = defaultTemplates()
        }
    }

    override fun noStateLoaded() {
        myState.templates = defaultTemplates()
    }
}
