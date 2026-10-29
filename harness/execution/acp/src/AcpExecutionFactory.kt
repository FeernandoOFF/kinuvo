@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package kinuvo.execution.acp

import com.agentclientprotocol.client.v2.*
import com.agentclientprotocol.model.v2.*
import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

class AcpExecutionFactory(private val probeTimeoutMillis: Long = 15_000) : ExecutionFactory {
    override suspend fun probe(config: HarnessConnection): ExecutionProbe {
        val acp = config.connection as? ExecutionConnection.Acp ?: return ExecutionProbe(ExecutionReadiness(true, false, "Remote adapter unavailable", "unsupported"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            withTimeoutOrNull(probeTimeoutMillis) {
                val connection = AcpConnection.open(scope, acp, object : ElicitationHandler {
                    override suspend fun createElicitation(request: CreateElicitationRequest) = CreateElicitationResponse(ElicitationAction.Decline)
                }, {})
                try {
                    val info = connection.client.agentInfo.await()
                    val auth = if (info.capabilities.auth?.status == true) connection.client.authStatus().authenticated else null
                    ExecutionProbe(ExecutionReadiness(true, auth != false, if (auth == false) "Sign in with the external harness" else null, if (auth == false) "needs_sign_in" else null), info.implementation.title ?: info.implementation.name, info.implementation.version, auth,
                        JsonArray(info.authMethods.map { ProtocolJson.encodeToJsonElement(AuthMethod.serializer(), it) }))
                } finally { connection.close() }
            } ?: ExecutionProbe(ExecutionReadiness(true, false, "ACP negotiation timed out", "unavailable"))
        } catch (e: CancellationException) { throw e }
        catch (e: java.io.IOException) { ExecutionProbe(ExecutionReadiness(true, false, "Command could not be started", "not_found")) }
        catch (e: Exception) { ExecutionProbe(ExecutionReadiness(true, false, "ACP negotiation failed", "incompatible")) }
        finally { scope.cancel() }
    }

    override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
        val acp = config.connection as? ExecutionConnection.Acp ?: error("Remote execution is not installed")
        val connection = AcpConnection.open(scope, acp, object : ElicitationHandler {
            override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
                val answer = broker.ask(ExecutionRequest(Uuid.random().toString(), ExecutionRequestKind.Question, ProtocolJson.encodeToJsonElement(CreateElicitationRequest.serializer(), request))) {
                    val response = ProtocolJson.decodeFromJsonElement(CreateElicitationResponse.serializer(), it)
                    require(response.action !is ElicitationAction.Unknown) { "Unknown elicitation action" }
                }
                return ProtocolJson.decodeFromJsonElement(CreateElicitationResponse.serializer(), answer)
            }
        }, broker::lost, cwd = registration.cwd)
        try {
            val operations = object : ClientSessionOperations {
                override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
                    val answer = broker.ask(ExecutionRequest(Uuid.random().toString(), ExecutionRequestKind.Approval, ProtocolJson.encodeToJsonElement(RequestPermissionRequest.serializer(), request), request.options.map { ExternalApprovalOption(it.optionId.toString(), it.name, ProtocolJson.encodeToJsonElement(PermissionOptionKind.serializer(), it.kind).jsonPrimitive.content) })) {
                        val response = ProtocolJson.decodeFromJsonElement(RequestPermissionResponse.serializer(), it)
                        val raw = ProtocolJson.encodeToJsonElement(RequestPermissionResponse.serializer(), response).jsonObject["outcome"]!!.jsonObject
                        require(raw["outcome"]?.jsonPrimitive?.content in setOf("selected", "cancelled")) { "Unknown permission outcome" }
                        raw["optionId"]?.jsonPrimitive?.content?.let { id -> require(request.options.any { it.optionId.toString() == id }) { "Unknown permission option" } }
                    }
                    return ProtocolJson.decodeFromJsonElement(RequestPermissionResponse.serializer(), answer)
                }
            }
            val previous = state.snapshot().externalSessionId
            val session = if (previous == null) connection.newSession(requireNotNull(registration.cwd), operations)
                else connection.resumeSession(previous, requireNotNull(registration.cwd), operations)
            state.reference(session.sessionId.toString())
            val adapter = AcpSession(session, state, broker, ExecutionCapabilities(resume = connection.supportsResume(), cancel = true, questions = true, approvals = true))
            val collecting = scope.launch {
                try { adapter.collect { update ->
                    val raw = ProtocolJson.encodeToJsonElement(com.agentclientprotocol.model.v2.SessionUpdate.serializer(), update).jsonObject
                    val kind = raw["sessionUpdate"]?.jsonPrimitive?.content ?: return@collect
                    if (kind == "tool_call_update" || kind == "tool_call_content_chunk") {
                        val id = raw["toolCallId"]?.jsonPrimitive?.content ?: return@collect
                        val old = state.snapshot().messages.firstOrNull { it.id == "tool:$id" }
                        val payload = JsonObject(old?.payload?.jsonObject.orEmpty() + raw.filterKeys { it != "sessionUpdate" })
                        state.message(ExecutionMessage("tool:$id", "tool", payload["title"]?.jsonPrimitive?.content ?: old?.text ?: "Tool", payload))
                        return@collect
                    }
                    val role = when { kind.startsWith("agent_message") -> "assistant"; kind.startsWith("agent_thought") -> "reasoning"; kind.startsWith("user_message") -> "user"; else -> return@collect }
                    val id = raw["messageId"]?.jsonPrimitive?.content ?: return@collect
                    val content = raw["content"] ?: return@collect
                    fun text(element: JsonElement): String = when(element) {
                        is JsonArray -> element.joinToString("") { text(it) }
                        is JsonObject -> element["text"]?.jsonPrimitive?.content.orEmpty()
                        else -> ""
                    }
                    val previous = state.snapshot().messages.firstOrNull { it.id == id }?.text.orEmpty()
                    state.message(ExecutionMessage(id, role, (if (kind.endsWith("_chunk")) previous else "") + text(content)))
                } } finally { withContext(NonCancellable) { try { broker.lost() } finally { connection.close() } } }
            }
            return object : ExecutionSession by adapter {
                override suspend fun close() { collecting.cancelAndJoin(); connection.close() }
            }
        } catch(e: Throwable) { connection.close(); throw e }
    }
}
