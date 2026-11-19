package kinuvo.gateways.mcp

import kinuvo.harness.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.server.AgentRuntime
import kinuvo.server.execution.serverToolAgent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Host-installed decorator; embedded Servers never install it. */
class McpExecutionFactory(
    private val delegate: ExecutionFactory,
    private val harness: AgentRuntime,
    private val access: McpAccess,
    private val url: () -> String,
) : ExecutionFactory {
    private inner class Lease {
        private val credential = McpAccess.Session()
        private var token: String? = null
        private var revision = 0
        private var closed = false

        @Synchronized fun revision() = revision
        @Synchronized fun start(expected: Int, next: McpAccess.Run): String {
            if (closed || revision != expected || credential.run != null) {
                next.job.cancel()
                throw CancellationException("Run could not start")
            }
            credential.run = next
            return token ?: access.issue(credential).also { token = it }
        }

        @Synchronized fun end(expected: McpAccess.Run? = null): McpAccess.Run? {
            if (expected != null && credential.run !== expected) return null
            revision++
            return credential.run.also { credential.run = null; it?.job?.cancel() }
        }

        @Synchronized fun close(): McpAccess.Run? {
            closed = true
            access.revoke(credential)
            return end()
        }
    }
    private val leases = ConcurrentHashMap<SessionId, Lease>()
    init {
        harness.log.onEvent { _, id, event ->
            if (event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Lost) {
                leases.remove(id)?.close()
            } else if (event is AgentEvent.RunFinished || event is AgentEvent.RunError ||
                event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Idle) {
                leases[id]?.end()
            }
        }
    }

    override suspend fun probe(config: HarnessConnection) = delegate.probe(config)

    override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
        val lease = Lease()
        leases[state.sessionId] = lease
        val session = try { delegate.open(config, registration, state, broker, scope) }
        catch (e: Throwable) { leases.remove(state.sessionId, lease); lease.close(); throw e }
        if (!session.acceptsMcp) { leases.remove(state.sessionId, lease); return session }

        val toolInteractions = ConcurrentHashMap.newKeySet<InteractionId>()
        return object : ExecutionSession by session {
            override val capabilities = session.capabilities.copy(mcp = true)
            override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                val revision = lease.revision()
                // External Agents have Server tool defaults; their private tool configuration stays in the harness.
                val agent = registration.serverToolAgent()
                val composed = harness.composer.compose(agent, state.sessionId, routine = (input.kind as? RunKind.Routine)?.turn, requireModel = false)
                val sink = state.boundSink()
                val calls = mutableMapOf<ToolCallId, SessionEntry.ToolCall>()
                val turn = ToolTurn(emit = { event ->
                    when (event) {
                        is AgentEvent.InteractionRequested -> { toolInteractions += event.interaction.id; state.request(event.interaction) }
                        is AgentEvent.InteractionResolved -> { toolInteractions -= event.id; state.resolve(event.id) }
                        is AgentEvent.ToolCallStart -> {
                            calls[event.toolCallId] = SessionEntry.ToolCall(event.toolCallId, event.toolCallName, event.args, event.identity)
                            sink.publish(event)
                        }
                        else -> sink.publish(event)
                    }
                }, progress = { sink.publish(it) }, complete = { result ->
                    sink.publish(AgentEvent.ToolCallResult(result.id, result.output, result.isError, result.identity))
                    sink.commit(listOfNotNull(calls.remove(result.id), result))
                })
                val gate = object : ToolGate {
                    override suspend fun invoke(call: SessionEntry.ToolCall, turn: ToolTurn): SessionEntry.ToolResult {
                        try { return composed.gate.invoke(call, turn) }
                        catch (e: CancellationException) {
                            withContext(NonCancellable) {
                                toolInteractions.toList().forEach { state.resolve(it); toolInteractions -= it }
                                calls.values.toList().forEach {
                                    turn.complete(SessionEntry.ToolResult(it.id, it.name, "Cancelled by user", true, identity = it.identity))
                                }
                            }
                            throw e
                        }
                    }
                }
                val run = McpAccess.Run(composed.tools, gate, turn, SupervisorJob(scope.coroutineContext[Job]), composed.profile.resultLimits.maxTokens, attachmentBytes = harness.attachments::bytes, instructions = composed.context)
                val token = lease.start(revision, run)
                try { return session.prompt(input.copy(mcp = McpServerDescriptor(url(), "Bearer $token", composed.tools.tools.map { it.name }.toSet()))) }
                catch (e: Throwable) { lease.end(run)?.job?.join(); throw e }
            }
            override suspend fun cancel() { lease.end()?.job?.join(); session.cancel() }
            override suspend fun close() { leases.remove(state.sessionId, lease); lease.close()?.job?.join(); session.close() }
            override suspend fun respond(requestId: InteractionId, answer: InteractionAnswer): Boolean? {
                if (requestId !in toolInteractions) return session.respond(requestId, answer)
                val pending = state.snapshot().pending.firstOrNull { it.id == requestId }
                return if (pending is PendingInteraction.Approval || pending is PendingInteraction.Questions)
                    harness.respondToTool(pending, answer) else session.respond(requestId, answer)
            }
        }
    }
}
