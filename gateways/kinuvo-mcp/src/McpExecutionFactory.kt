package kinuvo.gateways.mcp

import kinuvo.harness.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.server.HarnessRuntime
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Host-installed decorator; embedded Servers never install it. */
class McpExecutionFactory(
    private val delegate: ExecutionFactory,
    private val harness: HarnessRuntime,
    private val access: McpAccess,
    private val url: () -> String,
) : ExecutionFactory {
    private inner class Lease {
        private var revision = 0
        private var run: McpAccess.Run? = null
        @Synchronized fun revision() = revision
        @Synchronized fun issue(expected: Int, next: McpAccess.Run): String {
            if (revision != expected) { access.revoke(next); throw CancellationException("Run ended during preparation") }
            check(run == null) { "A run is already active" }
            run = next
            return access.issue(next)
        }
        @Synchronized fun revoke(expected: McpAccess.Run? = null): McpAccess.Run? {
            if (expected != null && run !== expected) return null
            revision++
            return run.also { run = null; it?.let(access::revoke) }
        }
    }
    private val leases = ConcurrentHashMap<SessionId, Lease>()
    init {
        harness.log.onEvent { _, id, event ->
            if (event is AgentEvent.RunFinished || event is AgentEvent.RunError ||
                event is AgentEvent.ExecutionPhaseChanged && event.phase in setOf(ExecutionPhase.Idle, ExecutionPhase.Lost)) {
                leases[id]?.revoke()
            }
        }
    }

    override suspend fun probe(config: HarnessConnection) = delegate.probe(config)

    override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
        val lease = Lease()
        leases[state.sessionId] = lease
        val session = try { delegate.open(config, registration, state, broker, scope) }
        catch (e: Throwable) { leases.remove(state.sessionId, lease); lease.revoke(); throw e }
        if (!session.acceptsMcp) { leases.remove(state.sessionId, lease); return session }
        suspend fun revoke() { lease.revoke()?.job?.join() }
        val toolInteractions = ConcurrentHashMap.newKeySet<InteractionId>()
        return object : ExecutionSession by session {
            override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                val revision = lease.revision()
                // External Agents have Server tool defaults; their private tool configuration stays in the harness.
                val agent = Agent(registration.id, registration.name, AgentConfig(workspace = listOfNotNull(registration.cwd)))
                val composed = harness.compose(agent, state.sessionId, requireModel = false)
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
                        is AgentEvent.ToolCallResult -> {
                            sink.publish(event)
                            val call = calls.remove(event.toolCallId)
                            sink.commit(listOfNotNull(call, SessionEntry.ToolResult(event.toolCallId, call?.name.orEmpty(), event.content, event.isError, identity = event.identity)))
                        }
                        else -> sink.publish(event)
                    }
                }, progress = { sink.publish(it) })
                val gate = object : ToolGate {
                    override suspend fun invoke(call: SessionEntry.ToolCall, turn: ToolTurn): SessionEntry.ToolResult {
                        try { return composed.gate.invoke(call, turn) }
                        catch (e: CancellationException) {
                            withContext(NonCancellable) {
                                toolInteractions.toList().forEach { state.resolve(it); toolInteractions -= it }
                                calls.values.toList().forEach {
                                    turn.emit(AgentEvent.ToolCallResult(it.id, "Cancelled by user", true, it.identity))
                                }
                            }
                            throw e
                        }
                    }
                }
                val run = McpAccess.Run(composed.tools, gate, turn, SupervisorJob(scope.coroutineContext[Job]), composed.profile.resultLimits.maxTokens)
                val token = lease.issue(revision, run)
                try { return session.prompt(input.copy(mcp = McpServerDescriptor(url(), "Bearer $token", composed.tools.tools.map { it.name }.toSet()))) }
                catch (e: Throwable) { lease.revoke(run)?.job?.join(); throw e }
            }
            override suspend fun cancel() { revoke(); session.cancel() }
            override suspend fun close() { revoke(); leases.remove(state.sessionId, lease); session.close() }
            override suspend fun respond(requestId: InteractionId, answer: InteractionAnswer): Boolean? {
                if (requestId !in toolInteractions) return session.respond(requestId, answer)
                val pending = state.snapshot().pending.firstOrNull { it.id == requestId }
                return if (pending is PendingInteraction.Approval || pending is PendingInteraction.Questions)
                    harness.respondToTool(pending, answer) else session.respond(requestId, answer)
            }
        }
    }
}
