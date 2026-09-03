package com.cliq.plugin.history

import com.cliq.plugin.settings.CliqSettings
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
        const val MAX_ENTRY_LENGTH = 8_192
        const val TRUNCATION_MARKER = "… [truncated]"
    }

    private val lock = Any()

    @Volatile
    private var myState = State()

    fun entries(): List<PromptHistoryEntry> = synchronized(lock) {
        myState.entries.map { it.copy() }
    }

    fun record(text: String) {
        if (!CliqSettings.getInstance().promptHistoryEnabled) return

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val capped = truncate(trimmed)

        synchronized(lock) {
            val updated = ArrayList<PromptHistoryEntry>(myState.entries.size + 1)
            updated.add(PromptHistoryEntry(capped, System.currentTimeMillis()))
            myState.entries.forEach { existing ->
                if (existing.text != capped) updated.add(existing.copy())
            }
            while (updated.size > MAX_ENTRIES) updated.removeAt(updated.lastIndex)
            myState.entries = updated
        }
    }

    fun clear() {
        synchronized(lock) { myState.entries = mutableListOf() }
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_ENTRY_LENGTH) return text
        var cut = MAX_ENTRY_LENGTH
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        return text.take(cut) + TRUNCATION_MARKER
    }

    override fun getState(): State = synchronized(lock) {
        State().apply { entries = myState.entries.map { it.copy() }.toMutableList() }
    }

    override fun loadState(state: State) {
        synchronized(lock) { myState = state }
    }
}
