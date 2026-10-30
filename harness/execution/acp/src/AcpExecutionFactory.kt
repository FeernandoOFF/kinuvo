@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package kinuvo.execution.acp

import com.agentclientprotocol.client.v2.*
import com.agentclientprotocol.model.v2.*
import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

class AcpExecutionFactory(private val probeTimeoutMillis: Long = 15_000,
    private val persistAttachment: suspend (ExecutionState, AttachmentData) -> Attachment = { _, _ -> error("Output attachment persistence is unavailable") },
) : ExecutionFactory {
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
        val requests = AcpRequests(broker)
        val reducer = AcpReducer(state) { persistAttachment(state, it) }
        val connection = AcpConnection.open(scope, acp, object : ElicitationHandler {
            override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
                val source = ExecutionRequest(Uuid.random().toString(), ExecutionRequestKind.Question,
                    ProtocolJson.encodeToJsonElement(CreateElicitationRequest.serializer(), request))
                val prompt = source.decodePrompt()
                val answer = requests.ask(source.copy(prompt = prompt), prompt)
                return ProtocolJson.decodeFromJsonElement(CreateElicitationResponse.serializer(), prompt.encodeAnswer(answer))
            }
        }, { reducer.lost(broker::lost) }, cwd = registration.cwd)
        try {
            val operations = object : ClientSessionOperations {
                override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
                    val source = ExecutionRequest(Uuid.random().toString(), ExecutionRequestKind.Approval,
                        ProtocolJson.encodeToJsonElement(RequestPermissionRequest.serializer(), request), request.options.map {
                            ExternalApprovalOption(it.optionId.toString(), it.name, ProtocolJson.encodeToJsonElement(PermissionOptionKind.serializer(), it.kind).jsonPrimitive.content)
                        })
                    val prompt = source.decodePrompt()
                    val answer = requests.ask(source.copy(prompt = prompt), prompt)
                    return ProtocolJson.decodeFromJsonElement(RequestPermissionResponse.serializer(), prompt.encodeAnswer(answer))
                }
            }
            val previous = state.snapshot().externalSessionId
            val session = if (previous == null) connection.newSession(requireNotNull(registration.cwd), operations)
                else connection.resumeSession(previous, requireNotNull(registration.cwd), operations)
            state.reference(session.sessionId.toString())
            val info = connection.client.agentInfo.await()
            val adapter = AcpSession(session, connection, state, broker, reducer, requests::cancel, ExecutionCapabilities(
                resume = connection.supportsResume(), cancel = true, questions = true, approvals = true,
                supportedMimeTypes = info.capabilities.session?.prompt.supportedMimeTypes()))
            val collecting = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { adapter.collect() }
                finally { withContext(NonCancellable) { try { reducer.lost(broker::lost) } finally { connection.close() } } }
            }
            return object : ExecutionSession by adapter {
                override suspend fun close() { collecting.cancelAndJoin(); connection.close() }
            }
        } catch(e: Throwable) { reducer.lost(broker::lost); connection.close(); throw e }
    }
}
