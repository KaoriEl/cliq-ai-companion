package com.cliq.plugin.mcp

import com.cliq.plugin.context.OpenFilesTracker
import com.cliq.plugin.context.WorkspaceContext
import com.cliq.plugin.diff.CliqDiffManager
import com.cliq.plugin.diff.CliqDiffOutcome
import com.cliq.plugin.mcp.transport.HttpExchangeAdapter
import com.cliq.plugin.mcp.transport.StreamableHttpServerTransport
import com.cliq.plugin.mcp.transport.reject
import com.cliq.plugin.settings.CliqSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson as SdkMcpJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Project-scoped MCP-over-HTTP server that lets external CLI agents (Gemini,
 * Claude) drive the Cliq diff manager and observe the editor's open files.
 *
 * Uses the MCP Kotlin SDK's Server + StreamableHttpServerTransport so that
 * notifications (ide/diffAccepted, ide/diffClosed, ide/contextUpdate) are
 * delivered correctly over the SSE channel the agent holds open.
 */
@Service(Service.Level.PROJECT)
class CliqIdeServer(private val project: Project) : Disposable {

    private val log = logger<CliqIdeServer>()
    private val authToken: String = generateAuthToken()
    private val transports = mutableMapOf<String, StreamableHttpServerTransport>()
    private val sessionsWithInitialNotification = mutableSetOf<String>()
    private val keepAliveJobs = mutableMapOf<String, Job>()
    private val mcpServer = createMcpServer()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var server: HttpServer? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var workspacePath: String = ""
    private val discoveryFiles = mutableListOf<File>()

    companion object {
        private const val KEEP_ALIVE_INTERVAL_MS = 30_000L
    }

    fun start() {
        if (server != null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                httpServer.createContext("/mcp", McpHandler())
                httpServer.executor = Executors.newCachedThreadPool { r ->
                    Thread(r, "Cliq-IdeServer").apply { isDaemon = true }
                }
                httpServer.start()
                server = httpServer
                boundPort = httpServer.address.port
                log.info("Cliq IDE server listening on 127.0.0.1:$boundPort")

                workspacePath = resolveWorkspacePath()
                writeDiscoveryFiles()
                wireListeners()
            } catch (t: Throwable) {
                log.warn("Failed to start Cliq IDE server", t)
            }
        }
    }

    fun port(): Int? = boundPort
    fun authToken(): String = authToken
    fun workspacePath(): String = workspacePath

    private inner class McpHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            coroutineScope.launch {
                try {
                    if (!exchange.requestURI.path.startsWith("/mcp")) {
                        sendResponse(exchange, 404, "Not Found")
                        return@launch
                    }

                    val authHeader = exchange.requestHeaders.getFirst("Authorization")
                    if (authHeader != "Bearer $authToken") {
                        sendResponse(exchange, 401, "Unauthorized")
                        return@launch
                    }

                    val adapter = HttpExchangeAdapter(exchange)
                    when (exchange.requestMethod.uppercase()) {
                        "POST" -> handleMcpPostRequest(adapter)
                        "GET" -> handleMcpGetRequest(adapter)
                        "DELETE" -> handleMcpDeleteRequest(adapter)
                        else -> {
                            exchange.responseHeaders.add("Allow", "GET, POST, DELETE")
                            sendResponse(exchange, 405, "Method Not Allowed")
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error handling MCP request", e)
                    try {
                        sendResponse(exchange, 500, "Internal Server Error")
                    } catch (ioe: IOException) {
                        log.error("Failed to send error response", ioe)
                    }
                }
            }
        }

        private fun sendResponse(exchange: HttpExchange, code: Int, body: String) {
            try {
                exchange.sendResponseHeaders(code, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            } finally {
                exchange.close()
            }
        }
    }

    private fun createMcpServer(): Server {
        val server = Server(
            Implementation(name = "cliq-ide-server", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "openDiff",
            description = "(IDE Tool) Open a diff view to create or modify a file. Returns a notification once the diff has been accepted or rejected.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("newContent", buildJsonObject { put("type", "string") })
                },
                required = listOf("filePath", "newContent")
            )
        ) { request ->
            val filePath = (request.arguments["filePath"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("filePath is required and must be a string")
            val newContent = (request.arguments["newContent"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("newContent is required and must be a string")

            log.info("Tool openDiff called for $filePath")

            if (CliqSettings.getInstance().autoApplyChanges) {
                ApplicationManager.getApplication().invokeLater {
                    project.service<CliqDiffManager>().applyDirectly(filePath, newContent)
                }
            } else {
                ApplicationManager.getApplication().invokeLater {
                    project.service<CliqDiffManager>().showDiff(filePath, newContent)
                }
            }

            CallToolResult(content = emptyList())
        }

        server.addTool(
            name = "closeDiff",
            description = "Programmatically close a previously opened diff review without user input.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("suppressNotification", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("filePath")
            )
        ) { request ->
            val filePath = (request.arguments["filePath"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("filePath is required and must be a string")
            val suppressNotification =
                (request.arguments["suppressNotification"] as? JsonPrimitive)?.booleanOrNull ?: false
            val finalContent = project.service<CliqDiffManager>().closeDiff(filePath, suppressNotification)

            CallToolResult(content = listOf(TextContent(finalContent ?: "")))
        }

        return server
    }

    private suspend fun handleMcpPostRequest(adapter: HttpExchangeAdapter) {
        val sessionId = adapter.getRequestHeader("mcp-session-id")
        val transport = if (sessionId != null && transports.containsKey(sessionId)) {
            transports[sessionId]!!
        } else {
            val newTransport = StreamableHttpServerTransport(
                enableJsonResponse = false,
                allowedHosts = listOf("localhost", "127.0.0.1")
            )
            newTransport.setOnSessionInitialized { newSessionId ->
                log.info("New MCP session initialized: $newSessionId")
                transports[newSessionId] = newTransport
                startKeepAliveForSession(newSessionId, newTransport)
            }
            newTransport.setOnSessionClosed { closedSessionId ->
                log.info("MCP session closed: $closedSessionId")
                cleanupSession(closedSessionId)
            }
            mcpServer.connect(newTransport)
            newTransport
        }
        transport.handlePostRequest(adapter)
    }

    private suspend fun handleMcpGetRequest(adapter: HttpExchangeAdapter) {
        val sessionId = adapter.getRequestHeader("mcp-session-id")
        if (sessionId == null || !transports.containsKey(sessionId)) {
            reject(adapter, 400, ErrorCode.Unknown(-32001), "Invalid or missing session ID")
            return
        }
        val transport = transports[sessionId]!!
        transport.handleGetRequest(adapter)

        if (!sessionsWithInitialNotification.contains(sessionId)) {
            sendInitialIdeContextToSession(sessionId, transport)
            sessionsWithInitialNotification.add(sessionId)
        }
    }

    private suspend fun handleMcpDeleteRequest(adapter: HttpExchangeAdapter) {
        val sessionId = adapter.getRequestHeader("mcp-session-id")
        if (sessionId == null || !transports.containsKey(sessionId)) {
            reject(adapter, 400, ErrorCode.Unknown(-32001), "Invalid or missing session ID")
            return
        }
        transports[sessionId]!!.handleDeleteRequest(adapter)
    }

    private fun startKeepAliveForSession(sessionId: String, transport: StreamableHttpServerTransport) {
        val job = coroutineScope.launch {
            while (isActive && transports.containsKey(sessionId)) {
                try {
                    delay(KEEP_ALIVE_INTERVAL_MS)
                    transport.send(JSONRPCNotification(jsonrpc = "2.0", method = "ping"))
                } catch (e: Exception) {
                    log.warn("Failed to send keep-alive ping for session $sessionId: ${e.message}")
                    cleanupSession(sessionId)
                    break
                }
            }
        }
        keepAliveJobs[sessionId] = job
    }

    private fun sendInitialIdeContextToSession(sessionId: String, transport: StreamableHttpServerTransport) {
        if (sessionsWithInitialNotification.contains(sessionId)) return
        val notification = buildContextNotification(project.service<OpenFilesTracker>().snapshot())
        coroutineScope.launch {
            try {
                transport.send(notification)
                sessionsWithInitialNotification.add(sessionId)
                log.debug("Initial IDE context sent to session: $sessionId")
            } catch (e: Exception) {
                log.warn("Failed to send initial IDE context to session $sessionId: ${e.message}")
            }
        }
    }

    private fun cleanupSession(sessionId: String) {
        log.info("Cleaning up session: $sessionId")
        keepAliveJobs.remove(sessionId)?.cancel()
        sessionsWithInitialNotification.remove(sessionId)
        transports.remove(sessionId)
    }

    private fun wireListeners() {
        project.service<OpenFilesTracker>().addListener(object : OpenFilesTracker.WorkspaceContextListener {
            override fun onContextChanged(context: WorkspaceContext) {
                broadcastIdeContextUpdate()
            }
        })

        project.messageBus.connect(this).subscribe(CliqDiffManager.TOPIC, object : CliqDiffManager.DiffListener {
            override fun onDiffOutcome(outcome: CliqDiffOutcome) {
                if (outcome is CliqDiffOutcome.Rejected && outcome.suppressed) return
                val notification = when (outcome) {
                    is CliqDiffOutcome.Accepted -> JSONRPCNotification(
                        method = "ide/diffAccepted",
                        params = buildJsonObject {
                            put("filePath", outcome.filePath)
                            put("content", outcome.finalContent)
                        }
                    )
                    is CliqDiffOutcome.Rejected -> JSONRPCNotification(
                        method = "ide/diffClosed",
                        params = buildJsonObject { put("filePath", outcome.filePath) }
                    )
                }
                broadcastNotification(notification)
            }
        })

        project.messageBus.connect(this).subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                val newPath = resolveWorkspacePath()
                if (newPath != workspacePath) {
                    workspacePath = newPath
                    writeDiscoveryFiles()
                }
            }
        })
    }

    private fun broadcastIdeContextUpdate() {
        val context = project.service<OpenFilesTracker>().snapshot()
        broadcastNotification(buildContextNotification(context))
    }

    private fun buildContextNotification(context: WorkspaceContext): JSONRPCNotification {
        val payload = WorkspaceSnapshot(
            workspaceState = WorkspaceStatePayload(
                openFiles = context.openFiles.map { file ->
                    OpenFilePayload(
                        path = file.path,
                        timestamp = file.openedAt,
                        isActive = file.isActive,
                        cursor = file.cursor?.let { CursorPayload(it.line, it.column) },
                        selectedText = file.selectedText,
                    )
                },
                isTrusted = context.isTrusted,
            ),
        )
        return JSONRPCNotification(method = "ide/contextUpdate", params = SdkMcpJson.encodeToJsonElement(WorkspaceSnapshot.serializer(), payload))
    }

    private fun broadcastNotification(notification: JSONRPCNotification) {
        log.info("Broadcasting notification '${notification.method}' to ${transports.size} sessions")
        transports.forEach { (sessionId, transport) ->
            coroutineScope.launch {
                try {
                    transport.send(notification)
                } catch (e: Exception) {
                    log.warn("Failed to send notification to session $sessionId: ${e.message}")
                    cleanupSession(sessionId)
                }
            }
        }
    }

    private fun resolveWorkspacePath(): String {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        return if (roots.isNotEmpty()) roots.joinToString(File.pathSeparator) { it.path }
        else project.basePath ?: ""
    }

    private fun writeDiscoveryFiles() {
        val port = boundPort ?: return
        val info = ApplicationInfo.getInstance()
        val ideInfo = IdeInfo(
            info.versionName.lowercase(Locale.getDefault()).replace(" ", ""),
            info.fullApplicationName
        )
        val ppid = ProcessHandle.current().pid()
        val record = DiscoveryRecord(port, authToken, ppid, ideInfo, workspacePath)
        val json = McpJson.codec.encodeToString(DiscoveryRecord.serializer(), record)
        val dir = File(System.getProperty("java.io.tmpdir"), "gemini/ide").apply { mkdirs() }
        val pids = mutableSetOf(ppid).apply {
            ProcessHandle.of(ppid).ifPresent { h -> h.parent().ifPresent { add(it.pid()) } }
        }
        for (pid in pids) {
            val file = File(dir, "gemini-ide-server-$pid-$port.json")
            file.writeText(json)
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.deleteOnExit()
            discoveryFiles.add(file)
        }
        log.info("Wrote ${discoveryFiles.size} discovery file(s) to ${dir.absolutePath}")
    }

    override fun dispose() {
        transports.keys.toList().forEach { cleanupSession(it) }
        coroutineScope.cancel()
        server?.stop(0)
        discoveryFiles.forEach { runCatching { it.delete() } }
        discoveryFiles.clear()
    }

    private fun generateAuthToken(): String =
        Base64.getEncoder().withoutPadding()
            .encodeToString(ByteArray(32).apply { SecureRandom().nextBytes(this) })
}
