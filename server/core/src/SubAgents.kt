package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.protocol.agent.AgentInfo
import kinuvo.server.execution.AgentExecution
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.session.*
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.execution.ExecutionPhase
import kotlin.uuid.Uuid
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
    data class UnknownTurn(val id: SessionId, val turn: String) : SubAgentError(WireErrorCode.NotFound, "No turn '$turn' for child '$id'")
    data class Target(val target: String, val valid: String) : SubAgentError(WireErrorCode.NotFound, "Unknown or ambiguous Agent '$target'. Valid Agents: $valid")
    data object Depth : SubAgentError(WireErrorCode.Conflict, "Sub-agent nesting limit is 2")
    data object Budget : SubAgentError(WireErrorCode.Conflict, "Activation budget exhausted")
    data class Privacy(val agent: String) : SubAgentError(WireErrorCode.Forbidden, "$agent has no on-device model; Private Sub-agents run in Hidden Sessions on this device’s model")
    data object Delivering : SubAgentError(WireErrorCode.Conflict, "Private child delivery is in progress")
    data object Stopped : SubAgentError(WireErrorCode.Conflict, "The child turn was stopped. Send a queued follow-up to start a new turn.")
    data object Busy : SubAgentError(WireErrorCode.Conflict, "A follow-up is already queued")
}

/** Server-owned child Sessions. Linkage and terminal results survive the run that created them. */
class SubAgents internal constructor(
    private val harness: AgentRuntime,
    private val dispatch: AgentExecution,
    private val scope: CoroutineScope,
    private val agents: suspend () -> List<AgentInfo>,
    private val activationLimit: Int = 64,
) {
    var onBudgetExhausted: suspend (SessionId) -> Unit = {}
    private class Budget(var left: Int)
    private val lock = Mutex()
    private val delivering = mutableSetOf<SessionId>()
    private val stopped = mutableSetOf<SessionId>()
    private val deleting = mutableSetOf<SessionId>()
    private val budgets = mutableMapOf<SessionId, Budget>()
    private val groups = mutableMapOf<SessionId, SessionId>()
    suspend fun shareBudget(id: SessionId, root: SessionId) = lock.withLock { groups[id] = root; stopped -= id }
    suspend fun releaseBudget(id: SessionId) = lock.withLock {
        val root = groups.remove(id)
        if (root != null && root !in groups.values) budgets.remove(root)
    }
    private val wakes = mutableMapOf<SessionId, MutableList<SubAgentState>>()
    private val retryWakes = mutableMapOf<SessionId, SessionEntry.ToolReport>()
    private val retried = mutableSetOf<SessionId>()
    private val waking = mutableSetOf<SessionId>()
    private val waiters = mutableMapOf<SessionId, Int>()
    private val parents = mutableMapOf<SessionId, SessionParent>()
    private val queued = mutableMapOf<SessionId, Job>()
    private val interactions = mutableMapOf<Pair<SessionId, InteractionId>, SessionId>()
    private val changed = MutableStateFlow(0L)
    private val logger = KotlinLogging.logger("kinuvo.subagents")
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        harness.log.onEvent { _, s, e -> events.trySend { relay(s, e) } }
        scope.launch {
            for (event in events) {
                try { event() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { logger.warn(e) { "Could not relay Sub-agent event" } }
            }
        }
    }

    private suspend fun relay(session: SessionId, event: AgentEvent) {
        if (event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Lost) cancelChildren(session, includeSelf = false)
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

    suspend fun begin(agent: AgentId, id: SessionId, kind: Runs.Kind) = lock.withLock {
        if (id in deleting) throw CancellationException("Session is being deleted")
        val link = parents[id] ?: harness.store.session(agent, id).parent
        if (kind == Runs.Kind.User || kind == Runs.Kind.Routine || kind == Runs.Kind.Workflow) {
            stopped -= id
            retried -= id
            if (link == null && id !in groups) budgets[id] = Budget(activationLimit)
            scheduleWake(agent, id)
        } else if (id in stopped) {
            throw CancellationException("Parent run cancelled")
        }
        if (link != null) {
            parents[id] = link
            if (kind == Runs.Kind.Wake || kind == Runs.Kind.User) {
                val child = savedChildren(link.agentId, link.sessionId)[id]
                if (child != null && child.status != SubAgentStatus.Running && !child.delivered)
                    save(link.agentId, link.sessionId, child.copy(status = SubAgentStatus.Running, result = null, turnId = Uuid.random().toString()))
            }
        }
    }

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
        val parent = lock.withLock {
            ensureAvailable(parentId)
            harness.store.session(agentId, parentId)
        }
        val isPrivate = private || parent.hidden
        val depth = (parent.parent?.depth ?: 0) + 1
        if (depth > 2) raise(SubAgentError.Depth)
        val agents = agents()
        val agent = agents.firstOrNull { it.id == target }
            ?: agents.singleOrNull { it.name.equals(target.value, ignoreCase = true) }
            ?: raise(SubAgentError.Target(target.value, agents.joinToString { "${it.id.value} (${it.name})" }))
        val local = if (isPrivate) harness.store.agents().firstOrNull { it.id == agent.id }
            ?: raise(SubAgentError.Privacy(agent.name)) else null
        local?.let { harness.composer.requireOnDevice(it) }
        val link = SessionParent(agentId, parentId, callId, depth)
        val info = withContext(NonCancellable) {
            dispatch.createChild(agent.id, link, local?.config?.let {
                SessionSettings(model = it.model, reasoning = it.reasoning, approvals = it.approvals, provider = it.provider)
            })
        }
        val child = SubAgentState(info.id, agent.id, callId, goal, private = isPrivate, turnId = Uuid.random().toString())
        var registered = false
        try {
            lock.withLock {
                currentCoroutineContext().ensureActive()
                ensureAvailable(parentId)
                if (info.id in deleting) throw CancellationException("Child Session is being deleted")
                save(agentId, parentId, child)
                parents[info.id] = link
                registered = true
            }
            dispatch.sendChild(SendParams(agent.id, info.id, goal))
        } catch (e: CancellationException) {
            if (registered) withContext(NonCancellable) { finish(agent.id, info.id, true, null) }
            throw e
        } catch (e: Exception) {
            if (registered) finish(agent.id, info.id, false, e.message ?: "Child failed to start", wakeParent = false)
            throw e
        } finally {
            if (!registered) withContext(NonCancellable) { harness.log.deleteChildIfPresent(agent.id, info.id) }
        }
        return child
    }

    private fun ensureAvailable(id: SessionId) {
        if (id in deleting || id in stopped) throw CancellationException("Parent run cancelled")
    }

    private suspend fun save(agent: AgentId, parent: SessionId, child: SubAgentState, historical: Boolean = false) {
        if (parent in deleting) return
        val code = if (historical) "sub_agent_delivery" else "sub_agent"
        harness.log.append(agent, parent, SessionEntry.Notice("child-${child.sessionId}-${Uuid.random()}", code, "", child = child))
        if (!historical) harness.log.publish(agent, parent, AgentEvent.SubAgentChanged(child))
        changed.update { it + 1 }
    }

    private suspend fun childTurns(agent: AgentId, parent: SessionId): Collection<SubAgentState> =
        harness.store.entries(agent, parent).filterIsInstance<SessionEntry.Notice>().mapNotNull { it.child }
            .associateBy { it.sessionId to turnKey(it) }.values

    private suspend fun savedChildren(agent: AgentId, parent: SessionId) = childTurns(agent, parent).associateBy { it.sessionId }

    suspend fun list(agent: AgentId, parent: SessionId): Map<SessionId, SubAgentState> =
        savedChildren(agent, parent).mapValues { (_, child) -> child.forClient() }

    context(_: Raise<SubAgentError>)
    private suspend fun child(agent: AgentId, parent: SessionId, id: SessionId) = list(agent, parent)[id] ?: raise(SubAgentError.Unknown(id))

    private data class WaitResult(val children: List<SubAgentState>, val timedOut: Boolean)

    context(_: Raise<SubAgentError>)
    suspend fun wait(agent: AgentId, parent: SessionId, ids: List<SessionId>, timeoutSeconds: Long): List<SubAgentState> =
        waiting(agent, parent, ids, timeoutSeconds) { it.children }

    context(_: Raise<SubAgentError>)
    private suspend fun <T> waiting(agent: AgentId, parent: SessionId, requested: List<SessionId>, timeoutSeconds: Long, turnId: String? = null, result: suspend (WaitResult) -> T): T {
        require(requested.isNotEmpty() && requested.size <= 32)
        val ids = requested.distinct()
        require(turnId == null || ids.size == 1) { "Specify one child Session when using turn_id" }
        suspend fun select(): List<SubAgentState> = if (turnId == null) {
            val children = list(agent, parent)
            ids.map { children[it] ?: raise(SubAgentError.Unknown(it)) }
        } else {
            val id = ids.single()
            val states = childTurns(agent, parent).filter { it.sessionId == id }
            if (states.isEmpty()) raise(SubAgentError.Unknown(id))
            listOf(states.firstOrNull { turnKey(it) == turnId }?.forClient() ?: raise(SubAgentError.UnknownTurn(id, turnId)))
        }
        lock.withLock {
            select().forEach { state ->
                if (state.status == SubAgentStatus.Running && state.sessionId !in parents)
                    save(agent, parent, state.copy(status = SubAgentStatus.Cancelled, result = "Server restarted before the child finished"))
            }
            ids.forEach { waiters[it] = (waiters[it] ?: 0) + 1 }
        }
        suspend fun snapshot(): List<SubAgentState> = lock.withLock {
            select().map { state ->
                if (turnId == null && queued[state.sessionId]?.isActive == true)
                    state.copy(status = SubAgentStatus.Running, result = null) else state
            }
        }
        try {
            val completed = withTimeoutOrNull(timeoutSeconds.coerceIn(0, 45) * 1000) {
                while (true) {
                    val version = changed.value
                    val states = snapshot()
                    if (states.none { it.status == SubAgentStatus.Running }) return@withTimeoutOrNull states
                    changed.first { it != version }
                }
                error("unreachable")
            }
            val states = completed ?: snapshot()
            val output = result(WaitResult(states, completed == null && states.any { it.status == SubAgentStatus.Running }))
            lock.withLock {
                wakes[parent]?.removeAll { pending -> states.any {
                    sameTurn(it, pending) && it.status != SubAgentStatus.Running
                } }
            }
            return output
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    ids.forEach { id ->
                        val count = waiters.getValue(id)
                        if (count == 1) waiters.remove(id) else waiters[id] = count - 1
                    }
                    scheduleWake(agent, parent)
                }
            }
        }
    }

    context(_: Raise<SubAgentError>)
    suspend fun send(agent: AgentId, parent: SessionId, id: SessionId, callId: ToolCallId, text: String, steer: Boolean = false): String =
        followUp(agent, parent, id, callId, text, steer, null)

    context(_: Raise<SubAgentError>)
    private suspend fun followUp(agent: AgentId, parent: SessionId, id: SessionId, callId: ToolCallId, text: String, steer: Boolean, fallbackOf: SubAgentState?): String {
        require(text.isNotBlank())
        val previous = child(agent, parent, id)
        lock.withLock {
            if (id in delivering) raise(SubAgentError.Delivering)
            if (previous.delivered) raise(SubAgentError.Unknown(id))
            if (steer && (id in stopped || previous.status == SubAgentStatus.Cancelled)) raise(SubAgentError.Stopped)
            if (queued[id]?.isActive == true) raise(SubAgentError.Busy)
        }
        val link = harness.store.sessions(previous.agentId).firstOrNull { it.id == id }?.parent
            ?: raise(SubAgentError.Unknown(id))
        val input = SendParams(previous.agentId, id, text)
        if (steer && previous.status == SubAgentStatus.Running && dispatch.trySteer(input, allowed = { lock.withLock { id !in stopped } }) {
                throwing<SubAgentError, _> { followUp(agent, parent, id, callId, text, false, previous) }
            }) return "steer"

        lock.withLock {
            val latest = child(agent, parent, id)
            if (fallbackOf != null && (id in stopped || !sameTurn(latest, fallbackOf))) return "queue"
            if (steer && (id in stopped || latest.status == SubAgentStatus.Cancelled)) raise(SubAgentError.Stopped)
            if (id in deleting || parent in deleting) raise(SubAgentError.Unknown(id))
            if (id in delivering) raise(SubAgentError.Delivering)
            if (latest.delivered) raise(SubAgentError.Unknown(id))
            if (queued[id]?.isActive == true) raise(SubAgentError.Busy)
            parents[id] = link; stopped -= id
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val running = latest.copy(callId = callId, goal = text, status = SubAgentStatus.Running, result = null, turnId = Uuid.random().toString())
                var started = false
                try {
                    changed.first { list(agent, parent)[id]?.status != SubAgentStatus.Running }
                    dispatch.join(id)
                    lock.withLock { ensureActive(); save(agent, parent, running); started = true }
                    dispatch.sendChild(input)
                } catch (e: CancellationException) {
                    if (started) withContext(NonCancellable) { finish(previous.agentId, id, true, null) }
                    throw e
                } catch (e: Exception) {
                    if (started) finish(previous.agentId, id, false, e.message ?: "Child failed to start")
                }
            }
            queued[id] = job
            job.invokeOnCompletion { changed.update { it + 1 } }
            job.start()
        }
        return "queue"
    }

    suspend fun finish(agent: AgentId, id: SessionId, cancelled: Boolean, error: String?) =
        finish(agent, id, cancelled, error, wakeParent = true)

    private suspend fun finish(agent: AgentId, id: SessionId, cancelled: Boolean, error: String?, wakeParent: Boolean, drainEvents: Boolean = true) {
        val parent = lock.withLock { parents[id] } ?: return
        if (drainEvents) {
            val barrier = CompletableDeferred<Unit>()
            events.send { barrier.complete(Unit) }
            barrier.await()
        }
        val pending = lock.withLock {
            if (parent.sessionId in deleting) return
            val state = list(parent.agentId, parent.sessionId)[id] ?: return
            if (state.status != SubAgentStatus.Running) return
            val status = when {
                cancelled || id in stopped -> SubAgentStatus.Cancelled
                error != null -> SubAgentStatus.Failed
                else -> SubAgentStatus.Completed
            }
            val result = if (status == SubAgentStatus.Cancelled) null else error ?: harness.store.entries(agent, id)
                .takeLastWhile { it !is SessionEntry.UserMessage && (it !is SessionEntry.ToolReport || it.children.isEmpty()) }
                .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text
            if (state.private && result != null)
                harness.log.append(agent, id, SessionEntry.Notice(privateResultId(state), "sub_agent_result", result))
            val finished = state.copy(status = status, result = result.takeUnless { state.private })
            save(parent.agentId, parent.sessionId, finished)
            if (wakeParent && status != SubAgentStatus.Cancelled) {
                wakes.getOrPut(parent.sessionId) { mutableListOf() } += finished
                scheduleWake(parent.agentId, parent.sessionId)
            }
            interactions.filterValues { it == id }.keys.toList().also { keys -> keys.forEach(interactions::remove) }
        }
        pending.forEach { harness.log.publish(parent.agentId, parent.sessionId, AgentEvent.InteractionResolved(it.second)) }
    }

    // Called under lock. Keep one pending report per parent until a model request or a new turn takes it.
    private fun scheduleWake(agent: AgentId, parent: SessionId, afterRetry: SessionEntry.ToolReport? = null) {
        if (parent in deleting || parent in stopped || parent in delivering) return
        val retry = retryWakes[parent] != null
        if (!retry && wakes[parent].orEmpty().none { it.sessionId !in waiters }) return
        if (!waking.add(parent)) return
        scope.launch {
            delay(if (retry) 250 else 25)
            lock.withLock { waking -= parent }
            try {
                dispatch.report(agent, parent, { takeWake(agent, parent, afterRetry) }, { report, error ->
                    restoreWake(agent, parent, report, error)
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logger.warn(e) { "Sub-agent report could not start in Session $parent" }
            }
        }
    }

    private suspend fun takeWake(agent: AgentId, parent: SessionId, afterRetry: SessionEntry.ToolReport?): SessionEntry.ToolReport? {
        val completed = lock.withLock {
            if (parent in deleting || parent in stopped || parent in delivering) return null
            if (afterRetry != null && retryWakes[parent] == afterRetry) return null
            retryWakes.remove(parent)?.let {
                scheduleWake(agent, parent, afterRetry = it)
                return it
            }
            val pending = wakes[parent] ?: return null
            val ready = pending.filter { it.sessionId !in waiters }
            pending.removeAll(ready.toSet())
            if (pending.isEmpty()) wakes.remove(parent)
            if (ready.isNotEmpty()) retried -= parent
            ready
        }
        if (completed.isEmpty()) return null
        val names = agents().associate { it.id to it.name }
        var inlineBytes = 16 * 1024
        val children = completed.map { SubAgentCompletion(it.sessionId, it.agentId, it.callId,
            names[it.agentId] ?: it.agentId.value, it.status, it.private, it.turnId) }
        val content = children.zip(completed).joinToString("\n\n") { (child, state) ->
            val heading = "Sub-agent ${child.name} (${child.sessionId.value}) [${child.status.name.lowercase()}]."
            val read = "Use agents_wait with session_ids [\"${child.sessionId.value}\"] and turn_id \"${turnKey(state)}\" to read the result."
            val result = state.result.orEmpty()
            val bytes = if (result.length <= inlineBytes) result.encodeToByteArray().size else Int.MAX_VALUE
            when {
                child.private -> "$heading Result awaiting your approval. $read"
                result.isEmpty() -> heading
                bytes > inlineBytes -> "$heading $read"
                else -> { inlineBytes -= bytes; "$heading\n$result" }
            }
        }
        return SessionEntry.ToolReport(ToolNames.AGENTS_SPAWN, null, content, children = children)
    }

    private suspend fun restoreWake(agent: AgentId, parent: SessionId, report: SessionEntry.ToolReport, error: Exception) {
        val exhausted = lock.withLock {
            if (parent in deleting) return
            retryWakes[parent] = report
            // Retry once automatically. A later turn can still take the saved report.
            if (retried.add(parent)) {
                scheduleWake(agent, parent)
                false
            } else true
        }
        if (exhausted)
            finish(agent, parent, false, error.message ?: "Sub-agent wake failed to start", wakeParent = true, drainEvents = false)
    }

    context(_: Raise<SubAgentError>)
    suspend fun cancel(agent: AgentId, parent: SessionId, id: SessionId) {
        lock.withLock {
            child(agent, parent, id)
            stopped += id
            wakes[parent]?.removeAll { it.sessionId == id }
        }
        dispatch.stop(id)
    }

    internal suspend fun markStopped(id: SessionId) = lock.withLock { stopped += id }

    suspend fun cancelChildren(id: SessionId, includeSelf: Boolean = true, deletingSession: Boolean = false) {
        val children = lock.withLock {
            if (includeSelf) stopped += id
            if (deletingSession) {
                deleting += id
                wakes.remove(id)
                retryWakes.remove(id)
                retried -= id
                parents[id]?.let { parent -> wakes[parent.sessionId]?.removeAll { it.sessionId == id } }
            }
            queued.remove(id)?.cancel()
            parents.filterValues { it.sessionId == id }.keys.toList().also { ids ->
                ids.forEach { stopped += it; queued.remove(it)?.cancel() }
            }
        }
        if (!deletingSession && lock.withLock { id !in deleting }) children.forEach { dispatch.stop(it) }
    }

    suspend fun answer(answer: RespondParams): Boolean {
        val child = lock.withLock { interactions[answer.sessionId to answer.requestId] } ?: return false
        return dispatch.answer(answer.copy(sessionId = child))
    }

    private fun sameTurn(a: SubAgentState, b: SubAgentState) =
        a.sessionId == b.sessionId && turnKey(a) == turnKey(b)

    private fun turnKey(state: SubAgentState) = state.turnId ?: state.callId.value

    private fun privateResultId(state: SubAgentState) = "child-result:${turnKey(state)}"

    private suspend fun reviewed(agent: AgentId, parent: SessionId, call: ToolCallId, state: SubAgentState, transcriptExists: Boolean): SubAgentState {
        if (state.result == null || harness.store.session(agent, parent).hidden) return state
        val pending = harness.approvals.register()
        try {
            harness.log.publish(agent, parent, AgentEvent.InteractionRequested(PendingInteraction.Approval(
                pending.id, call, ToolNames.AGENTS_WAIT,
                ProtocolJson.encodeToString(SubAgentState.serializer(), state.copy(result = null)),
                risk = Risk.Medium, reason = "Share this Hidden Session result with the parent Agent", trustable = false,
                preview = if (transcriptExists)
                    ChangePreview("Share Sub-agent result", note = "Open the child Session ${state.sessionId.value} in this conversation to read its result before sharing.")
                else ChangePreview("Share Sub-agent result", body = state.result),
            ), state.sessionId.takeIf { transcriptExists }))
            val allowed = withTimeoutOrNull(1800_000) { pending.await() }?.let { it !is ApprovalDecision.Deny } == true
            return state.copy(result = if (allowed) state.result else "The user withheld the result")
        } finally {
            pending.close()
            withContext(NonCancellable) { harness.log.publish(agent, parent, AgentEvent.InteractionResolved(pending.id)) }
        }
    }

    private suspend fun deliver(agent: AgentId, parent: SessionId, call: ToolCallId, states: List<SubAgentState>): List<SubAgentState> =
        states.map { snapshot ->
            if (!snapshot.private || snapshot.delivered || snapshot.status == SubAgentStatus.Running) return@map snapshot
            val id = snapshot.sessionId
            val (state, historical) = lock.withLock {
                val turns = childTurns(agent, parent).filter { it.sessionId == id }
                val current = turns.firstOrNull { sameTurn(it, snapshot) } ?: return@map snapshot
                if (current.delivered) return@map current
                val historical = !sameTurn(turns.last(), current)
                if ((!historical && queued[id]?.isActive == true) || !delivering.add(id)) return@map snapshot
                current to historical
            }
            try {
                if (!historical) dispatch.join(id)
                val exists = harness.store.sessions(state.agentId).any { it.id == id }
                val raw = if (exists) harness.store.entries(state.agentId, id).filterIsInstance<SessionEntry.Notice>()
                    .lastOrNull { it.id == privateResultId(state) }?.message ?: state.result else state.result
                val result = reviewed(agent, parent, call, state.copy(result = raw), exists).copy(delivered = true)
                withContext(NonCancellable) {
                    val remove = lock.withLock {
                        if (parent in deleting) return@withLock false
                        val turns = childTurns(agent, parent).filter { it.sessionId == id }
                        if (turns.isEmpty()) return@withLock false
                        val historical = !sameTurn(turns.last(), state)
                        save(agent, parent, result, historical)
                        turns.none { !sameTurn(it, state) && !it.delivered && it.status != SubAgentStatus.Cancelled }
                    }
                    if (remove) {
                        if (exists) harness.log.deleteChildIfPresent(state.agentId, id)
                        lock.withLock {
                            parents.remove(id)
                            if (parent in deleting) return@withLock
                            val latest = savedChildren(agent, parent)[id]
                            if (latest != null && !latest.delivered) save(agent, parent, latest.copy(delivered = true))
                        }
                    }
                }
                result
            } finally {
                withContext(NonCancellable) { lock.withLock { delivering -= id; scheduleWake(state.agentId, id) } }
            }
        }

    private fun JsonObjectBuilder.putChildren(states: List<SubAgentState>) {
        put("children", ProtocolJson.encodeToJsonElement(ListSerializer(SubAgentState.serializer()), states))
    }

    fun tools(agent: AgentId, parent: SessionId): List<Tool> = listOf(
        agentTool(ToolNames.AGENTS_SPAWN, "Start a Sub-agent using its configured model and tools. A private child uses an on-device Hidden Session. Returns its Session id. $CHILD_NEXT", listOf(ToolParameter("goal", "Task and context for the child", ParameterType.String)), listOf(ToolParameter("agent_id", "Exact Agent id from agents_list. Omit to spawn yourself.", ParameterType.String), ToolParameter("private", "Private on-device Session. Default false.", ParameterType.Boolean))) { a, call ->
            val child = spawn(agent, parent, call, AgentId(a.string("agent_id") ?: agent.value), a.requireString("goal"), a["private"]?.jsonPrimitive?.booleanOrNull ?: false)
            val fields = ProtocolJson.encodeToJsonElement(SubAgentState.serializer(), child).jsonObject
            JsonObject(fields + ("next" to JsonPrimitive(CHILD_NEXT))).toString()
        },
        agentTool(ToolNames.AGENTS_WAIT, "Only when this turn needs the result now. Completion wakes you anyway. Don't poll. Returns repeatable child results.", listOf(ToolParameter("session_ids", "Child Session ids", ParameterType.List(ParameterType.String))), listOf(
            ToolParameter("timeout_seconds", "Default 30 seconds. Maximum 45 seconds.", ParameterType.Integer),
            ToolParameter("turn_id", "Read a specific turn from a wake. Use with one child Session id.", ParameterType.String),
        )) { a, call ->
            val ids = a.getValue("session_ids").jsonArray.map { SessionId(it.jsonPrimitive.content) }
            waiting(agent, parent, ids, a.string("timeout_seconds")?.toLongOrNull() ?: 30, a.string("turn_id")) { waited ->
                val states = deliver(agent, parent, call, waited.children)
                buildJsonObject {
                    putChildren(states)
                    put("timed_out", waited.timedOut)
                    if (states.any { it.status == SubAgentStatus.Running }) put("next", CHILD_NEXT)
                }.toString()
            }
        },
        agentTool(ToolNames.AGENTS_SEND, "Continue a child. Queue behind its current turn or steer it at the next safe point. Use agents_spawn for an independent re-check.", listOf(ToolParameter("session_id", "Child Session id", ParameterType.String), ToolParameter("text", "Follow-up message", ParameterType.String)), listOf(ToolParameter("mode", "queue (default) or steer. Falls back to queue if steering is unavailable.", ParameterType.Enum(arrayOf("queue", "steer"))))) { a, call ->
            val mode = a.string("mode") ?: "queue"
            require(mode == "queue" || mode == "steer") { "mode must be queue or steer" }
            val id = SessionId(a.requireString("session_id"))
            val accepted = send(agent, parent, id, call, a.requireString("text"), steer = mode == "steer")
            buildJsonObject { put("sessionId", id.value); put("mode", accepted); put("next", CHILD_NEXT) }.toString()
        },
        agentTool(ToolNames.AGENTS_CANCEL, "Cancel a child you no longer need. Cancellation does not wake this Session.", listOf(ToolParameter("session_id", "Child Session id", ParameterType.String)), listOf(ToolParameter("reason", "Why the work is no longer needed", ParameterType.String))) { a, _ ->
            val id = SessionId(a.requireString("session_id"))
            cancel(agent, parent, id)
            buildJsonObject {
                put("sessionId", id.value)
                put("cancel_requested", true)
                a.string("reason")?.let { put("reason", it) }
            }.toString()
        },
        agentTool(ToolNames.AGENTS_LIST, "List spawnable Agents (id, name, on_device) and this Session’s children.") { _, _ ->
            val native = harness.store.agents().associateBy { it.id }
            val agents = agents().map { candidate ->
                val local = native[candidate.id]?.let {
                    try { harness.composer.requireOnDevice(it); true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                } ?: false
                buildJsonObject {
                    put("id", candidate.id.value)
                    put("name", candidate.name)
                    put("on_device", local)
                }
            }
            val children = list(agent, parent).values.map { it.copy(result = null) }
            buildJsonObject {
                put("agents", JsonArray(agents))
                putChildren(children)
            }.toString()
        },
    )

    private fun agentTool(name: String, description: String, required: List<ToolParameter> = emptyList(), optional: List<ToolParameter> = emptyList(), action: suspend context(Raise<SubAgentError>) (JsonObject, ToolCallId) -> String): Tool = object : CoreTool {
        override val descriptor = toolSpec(name, description, requiredParameters = required, optionalParameters = optional)
        override suspend fun execute(args: JsonObject): String = error("A Sub-agent tool requires a call id")
        override suspend fun execute(args: JsonObject, callId: String): String = throwing<SubAgentError, _> { action(args, ToolCallId(callId)) }
    }
}

private const val CHILD_NEXT = "Completion wakes this Session. Do not wait on it or poll it. Continue other work, and end your turn when none is left."
