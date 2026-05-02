package com.cliq.plugin.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal object McpJson {
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
}

@Serializable
internal data class IdeInfo(val name: String, val displayName: String)

@Serializable
internal data class DiscoveryRecord(
    val port: Int,
    val authToken: String,
    val ppid: Long,
    val ideInfo: IdeInfo,
    val workspacePath: String,
)

@Serializable
internal data class WorkspaceSnapshot(
    val workspaceState: WorkspaceStatePayload?,
)

@Serializable
internal data class WorkspaceStatePayload(
    val openFiles: List<OpenFilePayload>,
    val isTrusted: Boolean,
)

@Serializable
internal data class OpenFilePayload(
    val path: String,
    val timestamp: Long,
    val isActive: Boolean,
    val cursor: CursorPayload? = null,
    val selectedText: String? = null,
)

@Serializable
internal data class CursorPayload(val line: Int, val character: Int)
