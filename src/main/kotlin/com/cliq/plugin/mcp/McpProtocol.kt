package com.cliq.plugin.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Lean implementation of the Model Context Protocol payloads we need to
 * speak to the Gemini and Claude CLIs.
 *
 * The full MCP SDK is overkill for our scope (two tools, three notification
 * types). Building the JSON-RPC envelopes by hand keeps the dependency
 * graph small and avoids leaking ktor + the MCP SDK into the plugin classpath.
 *
 * Wire format reference: https://spec.modelcontextprotocol.io/specification/
 */
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

/**
 * Payload written into the `${tmpdir}/gemini/ide/gemini-ide-server-PID-PORT.json`
 * discovery file. Gemini CLI scans these files (matched by its parent PID
 * walking the process tree) to locate a compatible IDE server.
 */
@Serializable
internal data class DiscoveryRecord(
    val port: Int,
    val authToken: String,
    val ppid: Long,
    val ideInfo: IdeInfo,
    val workspacePath: String,
)

/** Data the IDE pushes to the CLI describing the editor's currently open files. */
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

/**
 * Builders for JSON-RPC envelopes. Kept as plain string operations so the
 * server can write them straight to a `Writer` without intermediate parsing.
 */
internal object JsonRpc {
    private const val VERSION = "2.0"

    fun result(id: JsonElement, result: JsonElement): String =
        McpJson.codec.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", VERSION)
                put("id", id)
                put("result", result)
            },
        )

    fun error(id: JsonElement?, code: Int, message: String): String =
        McpJson.codec.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", VERSION)
                if (id != null) put("id", id) else put("id", JsonPrimitive(value = null as String?))
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            },
        )

    fun notification(method: String, params: JsonElement): String =
        McpJson.codec.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", VERSION)
                put("method", method)
                put("params", params)
            },
        )

    fun toolsList(tools: List<ToolDescriptor>): JsonElement = buildJsonObject {
        put("tools", buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                })
            }
        })
    }
}

/** Static description of a tool exposed by the IDE to the CLI. */
internal data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
) {
    companion object {
        fun openDiff(): ToolDescriptor = ToolDescriptor(
            name = "openDiff",
            description = "(IDE Tool) Open a diff view to create or modify a file. Returns a notification once the diff has been accepted or rejected.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("newContent", buildJsonObject { put("type", "string") })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("filePath"))
                    add(JsonPrimitive("newContent"))
                })
            },
        )

        fun closeDiff(): ToolDescriptor = ToolDescriptor(
            name = "closeDiff",
            description = "Programmatically close a previously opened diff review without user input.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("suppressNotification", buildJsonObject { put("type", "boolean") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("filePath")) })
            },
        )

    }
}

