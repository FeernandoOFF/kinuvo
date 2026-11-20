package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.session.*
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.execution.ExecutionPhase
import kotlin.uuid.Uuid
import kinuvo.protocol.approval.Risk
import kinuvo.server.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

sealed class SubAgentError(override val code: String, override val message: String) : ServerError {
    data class Unknown(val id: SessionId) : SubAgentError(WireErrorCode.NotFound, "No child '$id' in this Session")
    data class Target(val target: String, val valid: String) : SubAgentError(WireErrorCode.NotFound, "Unknown or ambiguous Agent '$target'. Valid Agents: $valid")
    data object Depth : SubAgentError(WireErrorCode.Conflict, "Sub-agent nesting limit is 2")
    data object Budget : SubAgentError(WireErrorCode.Conflict, "Activation budget exhausted")
    data class Privacy(val agent: String) : SubAgentError(WireErrorCode.Forbidden, "$agent has no on-device model; Private Sub-agents run in Hidden Sessions on this device’s model")
    data object Delivering : SubAgentError(WireErrorCode.Conflict, "Private child delivery is in progress")
    data object Busy : SubAgentError(WireErrorCode.Conflict, "A follow-up is already queued")
}

/** Server-owned child Sessions. Linkage and terminal results survive the run that created them. */
class SubAgents internal constructor(
    private val harness: AgentRuntime,
    private val dispatch: ExecutionDispatch,
    private val scope: CoroutineScope,
    private val join: suspend (SessionId) -> Unit,
    private val activationLimit: Int = 64,
) {
    var onBudgetExhausted: suspend (SessionId) -> Unit = {}
    private class Budget(var left: Int)
    private val lock = Mutex()
    private val delivering = mutableSetOf<SessionId>()
    private val stopped = mutableSetOf<SessionId>()
    private val budgets = mutableMapOf<SessionId, Budget>()
    private val groups = mutableMapOf<SessionId, SessionId>()
    suspend fun shareBudget(id: SessionId, root: SessionId) = lock.withLock { groups[id] = root; stopped -= id }
    suspend fun releaseBudget(id: SessionId) = lock.withLock {
        val root = groups.remove(id)
        if (root != null && root !in groups.values) budgets.remove(root)
    }
    private val parents = mutableMapOf<SessionId, SessionParent>()
    private val queued = mutableMapOf<SessionId, Job>()
    private val interactions = mutableMapOf<Pair<SessionId, InteractionId>, SessionId>()
    private val changed = MutableStateFlow(0L)
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        harness.log.onEvent { _, s, e -> events.trySend { relay(s, e) } }
        scope.launch { for (event in events) event() }
    }

    private suspend fun relay(session: SessionId, event: AgentEvent) {
        if (event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Lost) cancelChildren(session)
        val parent = lock.withLock { parents[session] } ?: return
        when (event) {
            is AgentEvent.InteractionRequested -> {
                lock.withLock { interactions[parent.sessionId to event.interaction.id] = session }
                harness.log.publish(parent.agentId, parent.sessionId, event.copy(childSessionId = event.childSessionId ?: session))
            }
            is AgentEvent.InteractionResolved -> {
                lock.withLock { interactions.remove(parent.sessionId to event.id) }
                harness.log.publish(parent.agentId, parent.sessionId, event)
            }
            else -> Unit
        }
    }

    suspend fun begin(id: SessionId) = lock.withLock {
        if (id !in parents && id !in groups) { budgets[id] = Budget(activationLimit); stopped -= id }
        else if (ancestors(id).any { it in stopped }) throw CancellationException("Parent run cancelled")
    }

    private fun ancestors(id: SessionId): List<SessionId> = parents[id]?.sessionId?.let { listOf(it) + ancestors(it) }.orEmpty()

    private fun root(id: SessionId): SessionId = parents[id]?.sessionId?.let(::root) ?: groups[id] ?: id

    suspend fun consume(id: SessionId) {
        val exhausted = lock.withLock {
            val budget = budgets.getOrPut(root(id)) { Budget(activationLimit) }
            (--budget.left < 0).let { if (it) root(id) else null }
        }
        if (exhausted != null) {
            onBudgetExhausted(exhausted)
            dispatch.stop(exhausted)
            throw CancellationException(SubAgentError.Budget.message)
        }
    }

    context(_: Raise<SubAgentError>)
    suspend fun spawn(agentId: AgentId, parentId: SessionId, callId: ToolCallId, target: AgentId, goal: String, private: Boolean = false): SubAgentState {
        require(goal.isNotBlank())
        val parent = harness.store.session(agentId, parentId)
        val isPrivate = private || parent.hidden
        val depth = (parent.parent?.depth ?: 0) + 1
        if (depth > 2) raise(SubAgentError.Depth)
        val agents = harness.store.agents()
        val agent = agents.firstOrNull { it.id == target }
            ?: agents.singleOrNull { it.name.equals(target.value, ignoreCase = true) }
            ?: raise(SubAgentError.Target(target.value, agents.joinToString { "${it.id.value} (${it.name})" }))
        if (isPrivate) harness.composer.requireOnDevice(agent)
        val link = SessionParent(agentId, parentId, callId, depth)
        val info = harness.log.createChild(agent.id, link, if (isPrivate) SessionSettings(model = agent.config.model,
            reasoning = agent.config.reasoning, approvals = agent.config.approvals, provider = agent.config.provider) else null)
        lock.withLock { parents[info.id] = link }
        val child = SubAgentState(info.id, agent.id, callId, goal, private = isPrivate)
        save(agentId, parentId, child)
        try {
            dispatch.send(SendParams(agent.id, info.id, goal))
        }
        catch (e: CancellationException) { withContext(NonCancellable) { finish(agent.id, info.id, true) }; throw e }
        catch (e: Exception) { save(agentId, parentId, child.copy(status = SubAgentStatus.Failed, result = e.message)); throw e }
        return child
    }

    private suspend fun save(agent: AgentId, parent: SessionId, child: SubAgentState) {
        harness.log.append(agent, parent, SessionEntry.Notice("child-${child.sessionId}-${Uuid.random()}", "sub_agent", "", child = child))
        harness.log.publish(agent, parent, AgentEvent.SubAgentChanged(child))
        changed.update { it + 1 }
    }

    suspend fun list(agent: AgentId, parent: SessionId): Map<SessionId, SubAgentState> =
        harness.store.entries(agent, parent).filterIsInstance<SessionEntry.Notice>().mapNotNull { it.child }.associateBy { it.sessionId }

    context(_: Raise<SubAgentError>)
    private suspend fun child(agent: AgentId, parent: SessionId, id: SessionId) = list(agent, parent)[id] ?: raise(SubAgentError.Unknown(id))

    context(_: Raise<SubAgentError>)
    suspend fun wait(agent: AgentId, parent: SessionId, ids: List<SessionId>, timeoutSeconds: Long): List<SubAgentState> {
        require(ids.isNotEmpty() && ids.size <= 32)
        ids.forEach { child(agent, parent, it) }
        ids.forEach { id ->
            val state = child(agent, parent, id)
            if (state.status == SubAgentStatus.Running && lock.withLock { id !in parents })
                save(agent, parent, state.copy(status = SubAgentStatus.Cancelled, result = "Server restarted before the child finished"))
        }
        withTimeoutOrNull(timeoutSeconds.coerceIn(0, 1800) * 1000) {
            while (true) {
                val version = changed.value
                if (ids.all { child(agent, parent, it).status != SubAgentStatus.Running && lock.withLock { queued[it]?.isActive != true } }) break
                changed.first { it != version }
            }
        }
        return ids.map { child(agent, parent, it) }
    }

    context(_: Raise<SubAgentError>)
    suspend fun send(agent: AgentId, parent: SessionId, id: SessionId, callId: ToolCallId, text: String) {
        require(text.isNotBlank())
        val previous = child(agent, parent, id)
        lock.withLock {
            if (id in delivering) raise(SubAgentError.Delivering)
            if (previous.delivered) raise(SubAgentError.Unknown(id))
            if (harness.store.sessions(previous.agentId).none { it.id == id }) raise(SubAgentError.Unknown(id))
            val link = harness.store.session(previous.agentId, id).parent ?: raise(SubAgentError.Unknown(id))
            parents[id] = link; stopped -= id
            if (queued[id]?.isActive == true) raise(SubAgentError.Busy)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var started = false
                try {
                    changed.first { list(agent, parent)[id]?.status != SubAgentStatus.Running }
                    join(id)
                    save(agent, parent, previous.copy(callId = callId, goal = text, status = SubAgentStatus.Running, result = null))
                    dispatch.send(SendParams(previous.agentId, id, text))
                    started = true
                } catch (e: CancellationException) {
                    if (!started) withContext(NonCancellable) {
                        save(agent, parent, previous.copy(callId = callId, status = SubAgentStatus.Cancelled, result = null))
                    }
                    throw e
                } catch (e: Exception) {
                    save(agent, parent, previous.copy(callId = callId, status = SubAgentStatus.Failed, result = e.message))
                }
            }
            queued[id] = job
            job.invokeOnCompletion { changed.update { it + 1 } }
            job.start()
        }
    }

    suspend fun finish(agent: AgentId, id: SessionId, cancelled: Boolean) {
        val barrier = CompletableDeferred<Unit>()
        events.send { barrier.complete(Unit) }
        barrier.await()
        val parent = lock.withLock { parents[id] } ?: return
        val state = list(parent.agentId, parent.sessionId)[id] ?: return
        val completion = harness.log.completion(id)
        val result = harness.store.entries(agent, id).takeLastWhile { it !is SessionEntry.UserMessage }
            .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text
        val status = when { cancelled -> SubAgentStatus.Cancelled; completion is AgentEvent.RunError -> SubAgentStatus.Failed; else -> SubAgentStatus.Completed }
        save(parent.agentId, parent.sessionId, state.copy(status = status, result = if (completion is AgentEvent.RunError) completion.message else result))
        val pending = lock.withLock { interactions.filterValues { it == id }.keys.toList().also { keys -> keys.forEach(interactions::remove) } }
        pending.forEach { harness.log.publish(parent.agentId, parent.sessionId, AgentEvent.InteractionResolved(it.second)) }
    }

    suspend fun cancelChildren(id: SessionId) {
        val children = lock.withLock { stopped += id; queued.remove(id)?.cancel(); parents.filterValues { it.sessionId == id }.keys.toList().also { ids -> ids.forEach { queued.remove(it)?.cancel() } } }
        children.forEach { dispatch.stop(it) }
    }

    suspend fun answer(answer: RespondParams): Boolean {
        val child = lock.withLock { interactions[answer.sessionId to answer.requestId] } ?: return false
        return dispatch.answer(answer.copy(sessionId = child))
    }

    private suspend fun reviewed(agent: AgentId, parent: SessionId, call: ToolCallId, states: List<SubAgentState>): List<SubAgentState> {
        if (harness.store.session(agent, parent).hidden) return states
        return states.map { state ->
            if (!state.private || state.delivered || state.result == null) return@map state
            val pending = harness.approvals.register()
            try {
                harness.log.publish(agent, parent, AgentEvent.InteractionRequested(PendingInteraction.Approval(
                    pending.id, call, ToolNames.AGENTS_WAIT,
                    ProtocolJson.encodeToString(SubAgentState.serializer(), state),
                    risk = Risk.Medium, reason = "Share this Hidden Session result with the parent Agent", trustable = false,
                    preview = ChangePreview("Share Sub-agent result", body = state.result),
                ), state.sessionId))
                val allowed = withTimeoutOrNull(1800_000) { pending.await() }?.let { it !is ApprovalDecision.Deny } == true
                state.copy(result = if (allowed) state.result else "The user withheld the result", delivered = true)
            } finally {
                pending.close()
                withContext(NonCancellable) { harness.log.publish(agent, parent, AgentEvent.InteractionResolved(pending.id)) }
            }
        }
    }

    private suspend fun deliver(agent: AgentId, parent: SessionId, call: ToolCallId, states: List<SubAgentState>): List<SubAgentState> =
        states.map { snapshot ->
            val (state, reserved) = lock.withLock {
                val current = list(agent, parent).getValue(snapshot.sessionId)
                val hidden = harness.store.sessions(current.agentId).any { it.id == current.sessionId && it.hidden }
                val state = current.copy(private = current.private || hidden)
                val reserve = state.private && !state.delivered && state.sessionId !in delivering && state.status != SubAgentStatus.Running && queued[state.sessionId]?.isActive != true
                if (reserve) delivering += state.sessionId
                state to reserve
            }
            if (!state.private || state.delivered) return@map state
            if (!reserved) return@map state.copy(result = null)
            try {
                join(state.sessionId)
                val result = reviewed(agent, parent, call, listOf(state.copy(private = true))).single().copy(delivered = true)
                save(agent, parent, result)
                if (harness.store.sessions(state.agentId).any { it.id == state.sessionId })
                    harness.log.deleteSession(state.agentId, state.sessionId)
                lock.withLock { parents.remove(state.sessionId) }
                result
            } finally {
                lock.withLock { delivering -= state.sessionId }
            }
        }

    fun tools(agent: AgentId, parent: SessionId): List<Tool> = listOf(
        agentTool(ToolNames.AGENTS_SPAWN, "Start a Sub-agent using the Agent’s configured model and tools; private opts into an on-device Hidden Session. Returns its Session id. Call agents_list first when unsure of an Agent id.", listOf(ToolParameter("goal", "Task and context for the child", ParameterType.String)), listOf(ToolParameter("agent_id", "Exact Agent id from agents_list; omit to spawn yourself.", ParameterType.String), ToolParameter("private", "Private on-device Session; default false", ParameterType.Boolean))) { a, call ->
            ProtocolJson.encodeToString(SubAgentState.serializer(), spawn(agent, parent, call, AgentId(a.string("agent_id") ?: agent.value), a.requireString("goal"), a["private"]?.jsonPrimitive?.booleanOrNull ?: false))
        },
        agentTool(ToolNames.AGENTS_WAIT, "Wait for children, up to 1800 seconds. Results are repeatable and keyed by Session id.", listOf(ToolParameter("session_ids", "Child Session ids", ParameterType.List(ParameterType.String))), listOf(ToolParameter("timeout_seconds", "Default 120; maximum 1800", ParameterType.String))) { a, call ->
            val ids = a.getValue("session_ids").jsonArray.map { SessionId(it.jsonPrimitive.content) }
            ProtocolJson.encodeToString(ListSerializer(SubAgentState.serializer()), deliver(agent, parent, call, wait(agent, parent, ids, a.string("timeout_seconds")?.toLongOrNull() ?: 120)))
        },
        agentTool(ToolNames.AGENTS_SEND, "Continue a child. A message to a running child queues behind its current turn.", listOf(ToolParameter("session_id", "Child Session id", ParameterType.String), ToolParameter("text", "Follow-up message", ParameterType.String))) { a, call ->
            send(agent, parent, SessionId(a.requireString("session_id")), call, a.requireString("text")); "Queued"
        },
        agentTool(ToolNames.AGENTS_LIST, "List spawnable Agents (id, name, on_device) and this Session’s children, or cancel a child.", optional = listOf(ToolParameter("cancel", "Child Session id to cancel", ParameterType.String))) { a, _ ->
            a.string("cancel")?.let { val id = SessionId(it); child(agent, parent, id); dispatch.stop(id) }
            val agents = harness.store.agents().map { candidate ->
                val local = try { harness.composer.requireOnDevice(candidate); true }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
                buildJsonObject {
                    put("id", candidate.id.value)
                    put("name", candidate.name)
                    put("on_device", local)
                }
            }
            val children = list(agent, parent).values.map { it.copy(result = null) }
            buildJsonObject {
                put("agents", JsonArray(agents))
                put("children", ProtocolJson.encodeToJsonElement(ListSerializer(SubAgentState.serializer()), children))
            }.toString()
        },
    )

    private fun agentTool(name: String, description: String, required: List<ToolParameter> = emptyList(), optional: List<ToolParameter> = emptyList(), action: suspend context(Raise<SubAgentError>) (JsonObject, ToolCallId) -> String): Tool = object : CoreTool {
        override val descriptor = toolSpec(name, description, requiredParameters = required, optionalParameters = optional)
        override fun assess(args: JsonObject) = Assessment(listOf(Action(
            if (name == ToolNames.AGENTS_LIST && args.string("cancel") != null) Risk.Medium else risk, listOf(name),
        )))
        override suspend fun execute(args: JsonObject): String = error("A Sub-agent tool requires a call id")
        override suspend fun execute(args: JsonObject, callId: String): String = throwing<SubAgentError, _> { action(args, ToolCallId(callId)) }
    }
}
