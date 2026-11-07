@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.harness.execution.acp

import com.agentclientprotocol.client.v2.*
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.json.JsonElement
import kinuvo.protocol.ProtocolJson
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kinuvo.harness.execution.api.KinuvoMcp
import kinuvo.protocol.ExecutionConnection
import java.io.File
import java.util.concurrent.TimeUnit

private const val KINUVO_MCP = "kinuvo"

/** JVM-only. Inherits the existing installation's environment and accounts; never installs a runtime. */
class AcpConnection private constructor(
    private val process: Process,
    private val protocol: Protocol,
    val client: Client,
) {
    suspend fun login(methodId: String) = client.login(AuthMethodId(methodId))

    suspend fun newSession(cwd: String, operations: ClientSessionOperations, tools: KinuvoMcp? = null): ClientSession {
        permissionHandler(operations)
        return client.newSession(cwd = cwd, mcpServers = mcpServers(tools), operations = operations)
    }

    /** HTTP when the harness advertises it, else the stdio bridge, which every ACP harness must support. */
    internal suspend fun mcpServers(tools: KinuvoMcp?): List<McpServer> {
        tools ?: return emptyList()
        if (client.agentInfo.await().capabilities.session?.mcp?.http != null)
            return listOf(McpServer.Http(KINUVO_MCP, tools.url, listOf(HttpHeader("Authorization", "Bearer ${tools.token}"))))
        val bridge = tools.bridge ?: return emptyList()
        return listOf(McpServer.Stdio(KINUVO_MCP, bridge.first(), bridge.drop(1) + listOf("--url", tools.url), listOf(EnvVariable("KINUVO_MCP_TOKEN", tools.token))))
    }

    // SDK permission routing requires an already-created ClientSession, unlike its update inbox.
    private fun permissionHandler(operations: ClientSessionOperations, sessionId: String? = null) {
        protocol.setRequestHandlerRaw(AcpMethod.ClientMethods.V2.SessionRequestPermission) { request ->
            val permission = ProtocolJson.decodeFromJsonElement(RequestPermissionRequest.serializer(), requireNotNull(request.params))
            require(sessionId == null || permission.sessionId.toString() == sessionId) { "Unexpected permission Session" }
            ProtocolJson.encodeToJsonElement(RequestPermissionResponse.serializer(), operations.requestPermission(permission))
        }
    }

    suspend fun supportsResume() = client.agentInfo.await().capabilities.session != null

    suspend fun resumeSession(id: String, cwd: String, operations: ClientSessionOperations, tools: KinuvoMcp? = null): ClientSession {
        check(supportsResume()) { "ACP harness did not negotiate Session resume support (capabilities.session)" }
        permissionHandler(operations, id)
        return client.resumeSession(SessionId(id), cwd, mcpServers = mcpServers(tools), replayFrom = null, operations = operations)
    }

    // SDK 0.33 drops PromptResponse and its unknown messageId field. Keep the raw acknowledgement.
    suspend fun prompt(session: ClientSession, content: List<ContentBlock>): JsonElement =
        protocol.sendRequestRaw(AcpMethod.AgentMethods.V2.SessionPrompt.methodName,
            ProtocolJson.encodeToJsonElement(PromptRequest.serializer(), PromptRequest(session.sessionId, content)), session.sessionId)

    fun cancel(session: ClientSession) = protocol.sendNotificationRaw(AcpMethod.AgentMethods.V2.SessionCancel,
        ProtocolJson.encodeToJsonElement(CancelSessionNotification.serializer(), CancelSessionNotification(session.sessionId)))

    suspend fun close() = cleanup(process, protocol)

    companion object {
        private suspend fun cleanup(process: Process, protocol: Protocol?) = withContext(NonCancellable) {
            try { withTimeoutOrNull(2_000) { protocol?.close() } }
            finally {
                withContext(Dispatchers.IO) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(2, TimeUnit.SECONDS)
                    }
                }
            }
        }

        suspend fun open(
            scope: CoroutineScope,
            config: ExecutionConnection.Acp,
            elicitation: ElicitationHandler,
            onLost: suspend () -> Unit,
            cwd: String? = null,
        ): AcpConnection {
            require(config.command.isNotEmpty())
            var process: Process? = null
            var protocol: Protocol? = null
            try {
                // Establish ownership before returning to a cancellable caller context.
                withContext(NonCancellable + Dispatchers.IO) {
                    process = ProcessBuilder(config.command).directory(cwd?.let(::File))
                        .apply { environment().putAll(config.environment) }.redirectError(ProcessBuilder.Redirect.DISCARD).start()
                }
                currentCoroutineContext().ensureActive()
                val owned = requireNotNull(process)
                val transport = StdioTransport(parentScope = scope, ioDispatcher = Dispatchers.IO, input = owned.inputStream.asSource().buffered(), output = owned.outputStream.asSink().buffered())
                val activeProtocol = Protocol(scope, transport)
                protocol = activeProtocol
                val client = Client(activeProtocol, elicitation)
                activeProtocol.start()
                client.initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("kinuvo", "0.1")))
                scope.launch {
                    withContext(Dispatchers.IO) { owned.waitFor() }
                    onLost()
                }
                return AcpConnection(owned, activeProtocol, client)
            } catch (e: Throwable) {
                process?.let { cleanup(it, protocol) }
                throw e
            }
        }
    }
}
