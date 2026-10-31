package kinuvo.harness.server.execution

import kinuvo.execution.api.*
import kinuvo.client.KinuvoClient
import kinuvo.client.ConnectionState
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** KMP adapter: a linked party receives no management credential or catalog secrets. */
class RemoteExecutionFactory(private val links: RemoteLinks, private val serverId: String, private val readiness: suspend (String, Boolean, String) -> Unit = { _, _, _ -> }) : ExecutionFactory {
    private suspend fun connect(id: String): KinuvoClient {
        val link = links.link(id)
        val client = KinuvoClient(link.url, "Kinuvo Server link", token = link.token, allowCleartext = true)
        try {
            val result = withTimeout(15_000) { client.connect() }
            require(result.capabilities.serverId != serverId) { "A Server cannot link to itself" }
            require(!result.management) { "Use an execution exposure token, not a management credential" }
            return client
        } catch (e: Throwable) { client.close(); throw e }
    }

    suspend fun discover(id: String): RemoteExecutionResult {
        val client = connect(id)
        try { return client.call(Api.RemoteExecution, RemoteExecutionCall()) }
        finally { client.close() }
    }

    override suspend fun probe(config: HarnessConnection): ExecutionProbe {
        val remote = config.connection as? ExecutionConnection.Remote ?: return ExecutionProbe(ExecutionReadiness(true, false, "Not a remote connection", "unsupported"))
        return try { discover(remote.serverId); ExecutionProbe(ExecutionReadiness(true, true)) }
        catch (e: CancellationException) { throw e }
        catch (_: kinuvo.client.AuthException) { ExecutionProbe(ExecutionReadiness(true, false, "Remote access revoked", "access_revoked")) }
        catch (_: Exception) { ExecutionProbe(ExecutionReadiness(true, false, "Remote Server unavailable", "host_offline")) }
    }

    override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
        val remote = config.connection as ExecutionConnection.Remote
        val client = connect(remote.serverId)
        try {
            val agentId = requireNotNull(registration.remoteAgentId)
            val created = client.call(Api.RemoteExecution, RemoteExecutionCall(agentId, action = "create"))
            val snapshot = requireNotNull(created.snapshot)
            val sessionId = snapshot.sessionId
            state.reference(sessionId)
            val sink = state.boundSink()
            val queue = Mutex()
            val commands = Mutex()
            val committed = mutableMapOf<String, ExecutionMessage>()
            var pending = emptyList<ExecutionRequest>()
            var activeTurn = false
            suspend fun reconcile(snapshot: ExecutionSnapshot) = queue.withLock {
                require(committed.keys.all { key -> snapshot.messages.any { it.id == key } }) { "Remote committed history disappeared" }
                val fresh = snapshot.messages.filter { it.id !in committed }
                require(snapshot.messages.none { committed[it.id]?.let { old -> old != it } == true }) { "Remote committed history changed" }
                pending.filter { old -> snapshot.pending.none { it.id == old.id } }.forEach { state.resolve(it.id) }
                snapshot.pending.filter { request -> pending.none { it.id == request.id } }.forEach { state.request(it) }
                pending = snapshot.pending
                if (snapshot.phase == ExecutionPhase.Lost) { broker.lost(); return@withLock }
                if (fresh.isNotEmpty() || snapshot.phase != ExecutionPhase.Idle) {
                    if (!activeTurn) { sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running)); activeTurn = true }
                    val entries = fresh.flatMap { m ->
                        if (m.role == "user") emptyList() else {
                            val native = m.payload?.let { runCatching { ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), it) }.getOrNull() }
                            val entries = if (native != null) listOf(native) else transcript(ExecutionSnapshot(sessionId, messages = listOf(m)))
                            entries.flatMap { entry -> if (entry is SessionEntry.ToolResult && entry.attachments.isNotEmpty()) listOf(
                                entry.copy(attachments = emptyList()),
                                SessionEntry.Notice("remote-media:${state.generation}:${entry.id}", "unsupported_content", "Attachments on the source Server cannot be transferred through this connection."),
                            ) else listOf(entry) }.map { entry -> when (entry) {
                                is SessionEntry.AssistantMessage -> entry.copy(messageId = "remote:${state.generation}:${entry.messageId ?: m.id}")
                                is SessionEntry.Reasoning -> entry.copy(messageId = "remote:${state.generation}:${entry.messageId ?: m.id}")
                                is SessionEntry.ToolCall -> entry.copy(id = "remote:${state.generation}:${entry.id}")
                                is SessionEntry.ToolResult -> entry.copy(id = "remote:${state.generation}:${entry.id}")
                                else -> entry
                            } }
                        }
                    }
                    if (snapshot.phase == ExecutionPhase.Idle) {
                        val runId = "remote:${state.generation}:${Uuid.random()}"
                        val stopReason = snapshot.stopReason
                        if (stopReason?.startsWith("error:") == true) {
                            sink.commit(listOf(SessionEntry.Notice(runId, "remote_execution_failed", stopReason.removePrefix("error:"), entries)))
                            sink.publish(AgentEvent.RunError(runId, stopReason.removePrefix("error:")))
                        } else {
                            sink.commit(entries)
                            sink.publish(AgentEvent.RunFinished(runId))
                        }
                        fresh.forEach { committed[it.id] = it }
                        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
                        activeTurn = false
                    } else {
                        entries.forEach { sink.publish(AgentEvent.ExecutionEntryChanged(it)) }
                        sink.publish(AgentEvent.ExecutionPhaseChanged(snapshot.phase))
                    }
                } else if (activeTurn && snapshot.phase == ExecutionPhase.Idle) {
                    val runId = "remote:${state.generation}:${Uuid.random()}"
                    val draft = state.live().entries
                    if (draft.isNotEmpty() || snapshot.stopReason?.startsWith("error:") == true) {
                        sink.commit(listOf(SessionEntry.Notice(runId, "remote_output_unconfirmed", "The source ended this turn without confirming its live output.", draft)))
                        sink.publish(AgentEvent.RunError(runId, "Source output was not confirmed"))
                    } else sink.publish(AgentEvent.RunFinished(runId))
                    sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle)); activeTurn = false
                }
            }
            reconcile(snapshot)
            val polling = scope.launch {
                try {
                    while (isActive) {
                        delay(200)
                        if (client.state.value is ConnectionState.Connected) {
                            try {
                                val snapshot = requireNotNull(withTimeout(10_000) { client.call(Api.RemoteExecution, RemoteExecutionCall(agentId, sessionId, "snapshot")) }.snapshot)
                                reconcile(snapshot)
                                if (snapshot.phase != ExecutionPhase.Lost) readiness(config.id, true, "")
                            }
                            catch (_: kinuvo.client.DisconnectedException) { readiness(config.id, false, "host_offline") }
                            catch (_: TimeoutCancellationException) { readiness(config.id, false, "host_offline") }
                        } else if ((client.state.value as? ConnectionState.Disconnected)?.rejected == true) {
                            readiness(config.id, false, "access_revoked")
                            throw IllegalStateException("Remote access revoked")
                        } else readiness(config.id, false, "host_offline")
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    runCatching { sink.commit(listOf(SessionEntry.Notice("remote-history:${state.generation}", "remote_history_unavailable", "The source could not confirm its execution history.", state.live().entries))) }
                    broker.lost(); client.close()
                }
            }
            return object : ExecutionSession {
                override val capabilities = ExecutionCapabilities(cancel = true, questions = true, approvals = true)
                override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance = commands.withLock {
                    require(input.attachments.isEmpty()) { "Remote attachments are unsupported" }
                    client.call(Api.RemoteExecution, RemoteExecutionCall(agentId, sessionId, "send", input.text))
                    ExecutionAcceptance()
                }
                override suspend fun cancel() { commands.withLock { client.call(Api.RemoteExecution, RemoteExecutionCall(agentId, sessionId, "cancel")) } }
                override suspend fun respond(requestId: String, answer: JsonElement): Boolean {
                    val accepted = client.call(Api.RemoteExecution, RemoteExecutionCall(agentId, sessionId, "answer", requestId = requestId, answer = answer)).accepted
                    if (accepted) state.resolve(requestId)
                    return accepted
                }
                override suspend fun answer(requestId: String, answer: JsonElement) { respond(requestId, answer) }
                override suspend fun close() { polling.cancelAndJoin(); client.close() }
            }
        } catch (e: Throwable) { client.close(); throw e }
    }
}
