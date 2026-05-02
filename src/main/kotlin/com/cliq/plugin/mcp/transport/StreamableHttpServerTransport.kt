package com.cliq.plugin.mcp.transport

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicBoolean

internal const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"

public interface EventStore {
  public suspend fun storeEvent(streamId: String, message: JSONRPCMessage): String
  public suspend fun replayEventsAfter(
    lastEventId: String,
    sender: suspend (eventId: String, message: JSONRPCMessage) -> Unit,
  ): String
}

internal data class SessionContext(val adapter: HttpExchangeAdapter, var writer: OutputStreamWriter? = null)

@OptIn(ExperimentalUuidApi::class)
public class StreamableHttpServerTransport(
  private val enableJsonResponse: Boolean = false,
  private val enableDnsRebindingProtection: Boolean = false,
  private val allowedHosts: List<String>? = null,
  private val allowedOrigins: List<String>? = null,
  private val eventStore: EventStore? = null,
) : AbstractTransport() {
  public var sessionId: String? = null
    private set

  private var sessionIdGenerator: (() -> String)? = { Uuid.random().toString() }
  private var onSessionInitialized: ((sessionId: String) -> Unit)? = null
  private var onSessionClosed: ((sessionId: String) -> Unit)? = null

  private val started: AtomicBoolean = AtomicBoolean(false)
  private val initialized: AtomicBoolean = AtomicBoolean(false)

  internal val streamsMapping: java.util.concurrent.ConcurrentHashMap<String, SessionContext> = java.util.concurrent.ConcurrentHashMap()
  private val requestToStreamMapping: java.util.concurrent.ConcurrentHashMap<RequestId, String> = java.util.concurrent.ConcurrentHashMap()
  private val requestToResponseMapping: java.util.concurrent.ConcurrentHashMap<RequestId, JSONRPCMessage> = java.util.concurrent.ConcurrentHashMap()

  private val sessionMutex = Mutex()
  private val streamMutex = Mutex()

  private companion object {
    const val STANDALONE_SSE_STREAM_ID = "_GET_stream"
  }

  public fun setSessionIdGenerator(block: (() -> String)?) {
    sessionIdGenerator = block
  }

  public fun setOnSessionInitialized(block: ((String) -> Unit)?) {
    onSessionInitialized = block
  }

  public fun setOnSessionClosed(block: ((String) -> Unit)?) {
    onSessionClosed = block
  }

  override suspend fun start() {
    sessionMutex.withLock {
      if (started.get()) {
        throw IllegalStateException("StreamableHttpServerTransport already started!")
      }
      started.set(true)
    }
  }

  override suspend fun send(message: JSONRPCMessage) {
    val requestId: RequestId? = when (message) {
      is JSONRPCResponse -> message.id
      else -> null
    }

    if (requestId == null) {
      require(message !is JSONRPCResponse && message !is JSONRPCError) {
        "Cannot send a response on a standalone SSE stream unless resuming a previous client request"
      }
      val standaloneStream = streamsMapping[STANDALONE_SSE_STREAM_ID] ?: return
      emitOnStream(STANDALONE_SSE_STREAM_ID, standaloneStream, message)
      return
    }

    val streamId = requestToStreamMapping[requestId]
      ?: error("No connection established for request ID: $requestId")
    val activeStream = streamsMapping[streamId]

    if (!enableJsonResponse) {
      activeStream?.let { stream ->
        emitOnStream(streamId, stream, message)
      }
    }

    val isTerminated = message is JSONRPCResponse || message is JSONRPCError
    if (!isTerminated) return

    requestToResponseMapping[requestId] = message
    val relatedIds = requestToStreamMapping.filterValues { it == streamId }.keys

    val allResponseReady = relatedIds.all { requestToResponseMapping.containsKey(it) }
    if (!allResponseReady) return

    streamMutex.withLock {
      if (activeStream == null) error("No connection established for request ID: $requestId")

      if (enableJsonResponse) {
        activeStream.adapter.setResponseHeader("Content-Type", "application/json")
        sessionId?.let { activeStream.adapter.setResponseHeader(MCP_SESSION_ID_HEADER, it) }
        val responses = relatedIds
          .mapNotNull { requestToResponseMapping[it] }
          .map { McpJson.encodeToString(it) }
        val payload = if (responses.size == 1) responses.first() else "[${responses.joinToString(",")}]"
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        activeStream.adapter.sendResponseHeaders(200, payloadBytes.size.toLong())
        activeStream.adapter.getResponseBody().use { it.write(payloadBytes) }
      } else {
        activeStream.writer?.close()
        streamsMapping.remove(streamId)
      }

      relatedIds.forEach {
        requestToResponseMapping.remove(it)
        requestToStreamMapping.remove(it)
      }
    }
  }

  override suspend fun close() {
    streamMutex.withLock {
      streamsMapping.values.forEach {
        runCatching { it.writer?.close() }
        runCatching { it.adapter.close() }
      }
      streamsMapping.clear()
      requestToResponseMapping.clear()
      requestToStreamMapping.clear()
      _onClose()
    }
  }

  private fun parseAcceptHeader(value: String): List<String> =
      value.split(",").map { it.substringBefore(';').trim().lowercase() }

  suspend fun handlePostRequest(adapter: HttpExchangeAdapter) {
    try {
      val acceptHeader = adapter.getRequestHeader("Accept") ?: ""
      val parsedAccept = parseAcceptHeader(acceptHeader)
      val isAcceptEventStream = parsedAccept.any { it == "text/event-stream" }
      val isAcceptJson = parsedAccept.any { it == "application/json" || it == "*/*" }

      if (!isAcceptEventStream && !isAcceptJson) {
        reject(adapter, 406, ErrorCode.Unknown(-32000), "Not Acceptable: Client must accept both application/json and text/event-stream")
        return
      }

      if (adapter.getRequestHeader("Content-Type")?.startsWith("application/json") != true) {
        reject(adapter, 415, ErrorCode.Unknown(-32000), "Unsupported Media Type: Content-Type must be application/json")
        return
      }

      val messages = parseBody(adapter) ?: return
      val isInitializationRequest = messages.any { it is JSONRPCRequest && it.method == Method.Defined.Initialize.value }

      if (isInitializationRequest) {
        if (initialized.get() && sessionId != null) {
          reject(adapter, 400, ErrorCode.Defined.InvalidRequest, "Invalid Request: Server already initialized")
          return
        }
        if (messages.size > 1) {
          reject(adapter, 400, ErrorCode.Defined.InvalidRequest, "Invalid Request: Only one initialization request is allowed")
          return
        }

        sessionMutex.withLock {
          if (sessionId == null) {
            sessionId = sessionIdGenerator?.invoke()
            initialized.set(true)
            sessionId?.let { onSessionInitialized?.invoke(it) }
          }
        }
      } else {
        if (!validateSession(adapter) || !validateProtocolVersion(adapter)) return
      }

      val hasRequest = messages.any { it is JSONRPCRequest }
      if (!hasRequest) {
        adapter.sendResponseHeaders(202, -1)
        adapter.close()
        messages.forEach { _onMessage(it) }
        return
      }

      val streamId = Uuid.random().toString()
      streamMutex.withLock {
        if (!enableJsonResponse) {
          adapter.appendSseHeaders(sessionId)
          adapter.sendResponseHeaders(200, 0)
          val writer = OutputStreamWriter(adapter.getResponseBody(), StandardCharsets.UTF_8)
          val sessionContext = SessionContext(adapter, writer)
          streamsMapping[streamId] = sessionContext

          writer.write(": SSE connection established for POST request\n\n")
          writer.flush()
        } else {
          streamsMapping[streamId] = SessionContext(adapter)
        }
        messages.filterIsInstance<JSONRPCRequest>().forEach { requestToStreamMapping[it.id] = streamId }
      }

      messages.forEach { _onMessage(it) }

    } catch (e: Exception) {
      _onError(e)
      reject(adapter, 400, ErrorCode.Defined.ParseError, "Parse error: ${e.message}")
    }
  }

  suspend fun handleGetRequest(adapter: HttpExchangeAdapter) {
    if (enableJsonResponse) {
      reject(adapter, 405, ErrorCode.Unknown(-32000), "Method not allowed.")
      return
    }

    val acceptHeader = adapter.getRequestHeader("Accept") ?: ""
    if (!parseAcceptHeader(acceptHeader).any { it == "text/event-stream" }) {
      reject(adapter, 406, ErrorCode.Unknown(-32000), "Not Acceptable: Client must accept text/event-stream")
      return
    }

    if (!validateSession(adapter) || !validateProtocolVersion(adapter)) return

    if (streamsMapping.containsKey(STANDALONE_SSE_STREAM_ID)) {
      reject(adapter, 409, ErrorCode.Unknown(-32000), "Conflict: Only one SSE stream is allowed per session")
      return
    }

    adapter.appendSseHeaders(sessionId)
    adapter.sendResponseHeaders(200, 0)
    val writer = OutputStreamWriter(adapter.getResponseBody(), StandardCharsets.UTF_8)
    val sessionContext = SessionContext(adapter, writer)
    streamsMapping[STANDALONE_SSE_STREAM_ID] = sessionContext

    writer.write(": SSE connection established\n\n")
    writer.flush()
  }

  suspend fun handleDeleteRequest(adapter: HttpExchangeAdapter) {
    if (enableJsonResponse) {
      reject(adapter, 405, ErrorCode.Unknown(-32000), "Method not allowed.")
      return
    }

    if (!validateSession(adapter) || !validateProtocolVersion(adapter)) return
    sessionId?.let { onSessionClosed?.invoke(it) }
    close()
    adapter.sendResponseHeaders(200, -1)
    adapter.close()
  }

  private fun validateSession(adapter: HttpExchangeAdapter): Boolean {
    if (sessionIdGenerator == null) return true

    if (!initialized.get()) {
      reject(adapter, 400, ErrorCode.Unknown(-32000), "Bad Request: Server not initialized")
      return false
    }

    val headerId = adapter.getRequestHeader(MCP_SESSION_ID_HEADER)
    return when {
      headerId == null -> {
        reject(adapter, 400, ErrorCode.Unknown(-32000), "Bad Request: Mcp-Session-Id header is required")
        false
      }
      headerId != sessionId -> {
        reject(adapter, 404, ErrorCode.Unknown(-32001), "Session not found")
        false
      }
      else -> true
    }
  }

  private fun validateProtocolVersion(adapter: HttpExchangeAdapter): Boolean {
    val version = adapter.getRequestHeader(MCP_PROTOCOL_VERSION_HEADER) ?: LATEST_PROTOCOL_VERSION
    return when (version) {
      !in SUPPORTED_PROTOCOL_VERSIONS -> {
        reject(adapter, 400, ErrorCode.Unknown(-32000), "Bad Request: Unsupported protocol version")
        false
      }
      else -> true
    }
  }

  private fun parseBody(adapter: HttpExchangeAdapter): List<JSONRPCMessage>? {
    val body = adapter.getRequestBody().reader(StandardCharsets.UTF_8).use { it.readText() }
    return when (val element = McpJson.parseToJsonElement(body)) {
      is JsonObject -> listOf(McpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), element))
      is JsonArray -> McpJson.decodeFromJsonElement(serializer<List<JSONRPCMessage>>(), element)
      else -> {
        reject(adapter, 400, ErrorCode.Defined.InvalidRequest, "Invalid Request: unable to parse JSON body")
        null
      }
    }
  }

  private suspend fun emitOnStream(streamId: String, sessionContext: SessionContext, message: JSONRPCMessage) {
    val eventId = eventStore?.storeEvent(streamId, message)
    try {
      sessionContext.writer?.let { writer ->
        writeSSEEventToWriter(writer, McpJson.encodeToString(message), eventId)
      }
    } catch (e: Exception) {
      _onError(e)
      streamsMapping.remove(streamId)
      runCatching { sessionContext.writer?.close() }
      runCatching { sessionContext.adapter.close() }
    }
  }

  private fun writeSSEEventToWriter(writer: OutputStreamWriter, data: String, eventId: String?) {
    val eventData = buildString {
      append("event: message\n")
      if (eventId != null) {
        append("id: $eventId\n")
      }
      append("data: $data\n\n")
    }
    writer.write(eventData)
    writer.flush()
  }

  private fun HttpExchangeAdapter.appendSseHeaders(sessionId: String?) {
    this.setResponseHeader("Content-Type", "text/event-stream")
    this.setResponseHeader("Cache-Control", "no-cache, no-transform")
    this.setResponseHeader("Connection", "keep-alive")
    sessionId?.let { this.setResponseHeader(MCP_SESSION_ID_HEADER, it) }
  }
}

internal fun reject(adapter: HttpExchangeAdapter, status: Int, code: ErrorCode, message: String) {
  try {
    val response = JSONRPCResponse(
      id = RequestId.StringId("-1"),
      error = JSONRPCError(message = message, code = code),
    )
    val responseBytes = McpJson.encodeToString(response).toByteArray(StandardCharsets.UTF_8)
    adapter.setResponseHeader("Content-Type", "application/json")
    adapter.sendResponseHeaders(status, responseBytes.size.toLong())
    adapter.getResponseBody().use { it.write(responseBytes) }
  } finally {
    adapter.close()
  }
}
