package com.cliq.plugin.history

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@State(
    name = "CliqPromptHistory",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
@Service(Service.Level.PROJECT)
class CliqPromptHistory : PersistentStateComponent<CliqPromptHistory.State> {

    class State {
        var entries: MutableList<PromptHistoryEntry> = mutableListOf()
    }

    companion object {
        const val MAX_ENTRIES = 50
    }

    private var myState = State()

    fun entries(): List<PromptHistoryEntry> = myState.entries.toList()

    fun record(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        myState.entries.removeAll { it.text == trimmed }
        myState.entries.add(0, PromptHistoryEntry(trimmed, System.currentTimeMillis()))
        while (myState.entries.size > MAX_ENTRIES) {
            myState.entries.removeAt(myState.entries.lastIndex)
        }
    }

    fun clear() {
        myState.entries.clear()
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }
}
