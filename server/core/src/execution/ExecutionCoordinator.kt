package kinuvo.server.execution


import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.SessionError
import kinuvo.harness.liveVoiceInstructions
import kinuvo.harness.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.server.store.SessionCommitConflict
import kinuvo.server.SessionLog
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

fun AgentRegistration.serverToolAgent() = Agent(id, name, AgentConfig(workspace = listOfNotNull(cwd)))

@Serializable
data class ExternalSession(val agentId: AgentId, val connectionId: String, val snapshot: ExecutionSnapshot)

class ExecutionCoordinator(
    val registry: ExecutionRegistry,
    private val factory: ExecutionFactory?,
    private val scope: CoroutineScope,
    private val log: SessionLog,
    private val recovery: ExecutionRecoveryStore = MemoryExecutionRecovery(),
    private val resolve: (String) -> AttachmentData = { error("Attachments unavailable") },
    private val readinessChanged: suspend () -> Unit = {},
    private val toolCatalog: suspend (Agent, SessionId?) -> List<ResolvedTool> = { _, _ -> emptyList() },
) : ExecutionDispatch {
    private val lock = Mutex()
    private val journal = Mutex()
    private val records = recovery.load().associateBy { it.sessionId }.toMutableMap()
    private val live = mutableMapOf<SessionId, ExecutionSession>()
    private val states = mutableMapOf<SessionId, ExecutionState>()
    private val brokers = mutableMapOf<SessionId, ExecutionBroker>()
    private val dispatch = mutableMapOf<SessionId, Job>()
    private class Turn {
        val done = CompletableDeferred<Unit>()
        val submitted = CompletableDeferred<Boolean>()
        var cancelled = false
        var finishing = false
    }
    private val turns = mutableMapOf<SessionId, Turn>()
    internal var onFinish: suspend (AgentId, SessionId, Boolean, String?) -> Unit = { _, _, _, _ -> }

    internal suspend fun isActive(id: SessionId) = lock.withLock { id in turns }

    suspend fun join(id: SessionId) { lock.withLock { turns[id] }?.done?.await() }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun finish(agent: AgentId, id: SessionId, lost: Boolean) {
        val (turn, cancelled) = lock.withLock {
            val turn = turns[id] ?: return
            if (turn.finishing) return
            turn.finishing = true
            turn to turn.cancelled
        }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val error = (log.completion(id) as? AgentEvent.RunError)?.message
                    ?: if (lost) "Harness connection lost" else null
                onFinish(agent, id, cancelled, error)
            } finally {
                withContext(NonCancellable) { release(id, turn) }
            }
        }
    }

    private suspend fun release(id: SessionId, turn: Turn) {
        lock.withLock { if (turns[id] === turn) turns.remove(id) }
        turn.submitted.complete(false)
        turn.done.complete(Unit)
    }

    private val opening = mutableSetOf<SessionId>()

    suspend fun reload() { val loaded = recovery.load(); lock.withLock { records.clear(); records.putAll(loaded.associateBy { it.sessionId }) } }

    suspend fun recover() {
        for (saved in records.values.toList()) {
            try {
                var r = saved
                (r.inputCheckpoint ?: r.reportCheckpoint)?.let { log.appendAll(r.agentId, r.sessionId, listOf(it)) }
                for (submission in r.submissions.keys - r.acceptedSubmissions) {
                    log.appendAll(r.agentId, r.sessionId, listOf(SessionEntry.Notice("submission-uncertain:$submission", "submission_uncertain", "The harness did not confirm this submission before the connection ended. It was not resent.")))
                }
                r = r.copy(inputCheckpoint = null, reportCheckpoint = null)
                if (r.checkpoint.isNotEmpty()) {
                    try { log.appendAll(r.agentId, r.sessionId, r.checkpoint) }
                    catch (_: SessionCommitConflict) {
                        log.appendAll(r.agentId, r.sessionId, listOf(SessionEntry.Notice("conflict:${r.generation}", "commit_conflict", "Execution output conflicts with saved history.", r.checkpoint)))
                    }
                    r = r.copy(checkpoint = emptyList(), draft = emptyList())
                } else if (r.draft.isNotEmpty()) {
                    log.appendAll(r.agentId, r.sessionId, listOf(SessionEntry.Notice("interrupted:${r.generation}", "execution_interrupted", "Execution ended before this output could be confirmed.", r.draft)))
                    r = r.copy(draft = emptyList())
                }
                save(r.copy(lost = true))
                log.publish(r.agentId, r.sessionId, AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Lost))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                save(saved.copy(lost = true, failure = "Execution recovery failed: ${e.message}"))
                KotlinLogging.logger("kinuvo.harness.execution").error { "Recovery failed for ${saved.agentId}/${saved.sessionId}: ${e.message}" }
            }
        }
    }

    private suspend fun save(record: ExecutionRecovery) = journal.withLock {
        recovery.save(record)
        lock.withLock { records[record.sessionId] = record }
    }
    private suspend fun mutate(id: SessionId, change: (ExecutionRecovery) -> ExecutionRecovery): ExecutionRecovery = journal.withLock {
        val next = change(lock.withLock { records.getValue(id) })
        recovery.save(next)
        lock.withLock { records[id] = next }
        next
    }
    suspend fun currentStateOwner(state: ExecutionState): String = lock.withLock {
        states.entries.firstOrNull { it.value === state }?.let { records.getValue(it.key).agentId }?.value
            ?: error("Stale execution generation")
    }
    suspend fun owns(id: SessionId) = lock.withLock { id in records }
    suspend fun sessionAgent(id: SessionId) = lock.withLock { records.getValue(id).agentId }
    suspend fun inUse(connectionId: String) = lock.withLock { records.values.any { it.connection.id == connectionId && (it.sessionId in live || it.sessionId in opening) } }
    suspend fun agentInUse(agentId: AgentId) = lock.withLock { records.values.any { it.agentId == agentId && (it.sessionId in live || it.sessionId in opening) } }
    suspend fun record(id: SessionId) = lock.withLock { records.getValue(id) }
    suspend fun archives() = lock.withLock { records.values.filter { it.archived }.map { it.registration }.distinctBy { it.id } }
    /** [agentId]'s registration, including one archived in the catalog or kept only by its saved Sessions. */
    suspend fun registrationOrArchived(agentId: AgentId) =
        registry.registration(agentId) ?: registry.catalog().agents.firstOrNull { it.id == agentId && it.archived } ?: archives().firstOrNull { it.id == agentId }

    /** What an external Agent's [sessionId], or a new Session, can do while its connection has [readiness]. */
    suspend fun capabilities(agentId: AgentId, sessionId: SessionId?, readiness: ExecutionReadiness, server: ServerCapabilities, seq: Long): SessionCapabilities {
        val saved = sessionId?.let { session(agentId, it); record(it) }
        val registration = saved?.registration ?: registry.registration(agentId)
        val support = saved?.support ?: registration?.let { agent ->
            registry.catalog().connections.firstOrNull { it.config.id == agent.connectionId }?.capabilities
        }
        val tools = if (support?.mcp == true && registration != null) toolCatalog(registration.serverToolAgent(), sessionId) else emptyList()
        val availability = when {
            saved?.archived == true || readiness.reasonCode == "archived" -> CapabilityState.unavailable("Agent registration archived", "archived")
            saved?.failure != null -> CapabilityState.unavailable(saved.failure, "recovery_failed")
            saved?.closed == true -> CapabilityState.unavailable("Execution closed", "session_closed")
            saved?.lost == true -> CapabilityState.unavailable("Execution connection ended; saved history remains available", "session_lost")
            !readiness.online -> CapabilityState.unavailable(readiness.reason ?: "Check connection", readiness.reasonCode ?: "checking")
            else -> CapabilityState()
        }
        return SessionCapabilities(seq, server, availability, tools = tools, attachments = support?.supportedMimeTypes.orEmpty().associateWith { availability }, execution = saved?.support)
    }

    suspend fun restore(agentId: AgentId) { lock.withLock { records.values.filter { it.agentId == agentId && it.archived } }.forEach { saved -> mutate(saved.sessionId) { it.copy(archived = false) } } }
    suspend fun archive(agentId: AgentId) { lock.withLock { records.values.filter { it.agentId == agentId } }.forEach { save(it.copy(archived = true)) } }
    suspend fun session(agentId: AgentId, id: SessionId): SessionInfo {
        val r = record(id); require(r.agentId == agentId)
        return log.session(agentId, id).copy(legacyOrder = r.legacyOrder)
    }
    suspend fun snapshot(id: SessionId): ExecutionSnapshot {
        val r = record(id)
        return log.executionSnapshot(r.agentId, id).copy(externalSessionId = r.externalSessionId).let { if (r.lost || r.closed) it.copy(phase = ExecutionPhase.Lost) else it }
    }
    override suspend fun snapshot(agentId: AgentId, sessionId: SessionId): ExecutionSnapshot { session(agentId, sessionId); return snapshot(sessionId) }
    override suspend fun create(agentId: AgentId): SessionInfo = create(agentId, null)

    internal suspend fun create(agentId: AgentId, child: SessionParent?): SessionInfo {
        val config = registry.configured(agentId)
        val registration = requireNotNull(registry.registration(agentId))
        require(Regex("[a-z0-9][a-z0-9-]{0,31}").matches(agentId.value))
        val info = if (child == null) log.createSession(agentId, "") else log.createChild(agentId, child)
        save(ExecutionRecovery(agentId, info.id, config, registration, Uuid.random().toString()))
        open(record(info.id))
        return session(agentId, info.id)
    }
    context(_: Raise<SessionError>)
    suspend fun resume(id: SessionId): ExecutionSnapshot {
        val old = record(id)
        if (!old.lost || old.closed || old.archived) raise(SessionError.CannotResume)
        requireNotNull(old.externalSessionId)
        check(old.connection == registry.configured(old.agentId) && old.registration == registry.registration(old.agentId)) { "Execution configuration changed" }
        discard(id)
        sealInterrupted(id)
        log.appendAll(old.agentId, id, listOf(SessionEntry.Notice("gap:${Uuid.random()}", "history_gap", "The harness may restore context, but output produced while disconnected is unavailable.")))
        open(record(id).copy(generation = Uuid.random().toString(), lost = false, failure = null))
        return snapshot(id)
    }
    private suspend fun open(initial: ExecutionRecovery) {
        val id = initial.sessionId
        var r = initial
        var projection = LiveState()
        var revision = 0L
        var draftFlush: Job? = null
        var written = TimeSource.Monotonic.markNow()
        suspend fun persist() { r = mutate(id) { r.copy(submissions = it.submissions, acceptedSubmissions = it.acceptedSubmissions, inputCheckpoint = it.inputCheckpoint, reportCheckpoint = it.reportCheckpoint, archived = it.archived, closed = it.closed) }; written = TimeSource.Monotonic.markNow() }
        val sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) {
                revision++
                projection = projection.on(event)
                r = r.copy(draft = projection.entries, lost = projection.executionPhase == ExecutionPhase.Lost)
                if (event !is AgentEvent.TextMessageContent && event !is AgentEvent.ThinkingContent && event !is AgentEvent.ExecutionEntryChanged || written.elapsedNow().inWholeMilliseconds >= 250) {
                    draftFlush?.cancel(); persist()
                } else if (draftFlush?.isActive != true) draftFlush = scope.launch {
                    delay(250)
                    val observed = revision
                    val draft = projection.entries
                    mutate(id) { current ->
                        if (current.generation == initial.generation && revision == observed && current.checkpoint.isEmpty()) current.copy(draft = draft) else current
                    }
                }
                log.publish(r.agentId, id, event)
                if (event is AgentEvent.ExecutionPhaseChanged) {
                    if (event.phase == ExecutionPhase.Running || event.phase == ExecutionPhase.RequiresAction) {
                        lock.withLock {
                            if (states[id]?.generation == initial.generation)
                                turns.getOrPut(id) { Turn().also { it.submitted.complete(true) } }
                        }
                    }
                    if (event.phase == ExecutionPhase.Idle || event.phase == ExecutionPhase.Lost)
                        finish(r.agentId, id, event.phase == ExecutionPhase.Lost)
                    if (event.phase == ExecutionPhase.Lost) scope.launch { releaseLost(id, initial.generation); readinessChanged() }
                }
            }
            override suspend fun commit(entries: List<SessionEntry>) {
                revision++; draftFlush?.cancel()
                r = r.copy(checkpoint = entries); persist()
                try { log.appendAll(r.agentId, id, entries) }
                catch (e: SessionCommitConflict) {
                    val notice = SessionEntry.Notice("conflict:${r.generation}", "commit_conflict", "Execution output conflicts with saved history.", entries)
                    log.appendAll(r.agentId, id, listOf(notice))
                    projection = projection.on(notice)
                    r = r.copy(checkpoint = emptyList(), draft = projection.entries); persist()
                    throw e
                }
                entries.forEach { projection = projection.on(it) }
                r = r.copy(checkpoint = emptyList(), draft = projection.entries); persist()
            }
        }
        val state = ExecutionState(id, { snapshot ->
            r = mutate(id) { it.copy(externalSessionId = snapshot.externalSessionId, lost = snapshot.phase == ExecutionPhase.Lost) }
            if (snapshot.phase == ExecutionPhase.RequiresAction) log.publish(r.agentId, id, AgentEvent.ExecutionPhaseChanged(snapshot.phase))
        }, ExecutionSnapshot(id, externalSessionId = r.externalSessionId), r.generation, sink)
        val broker = ExecutionBroker(state)
        lock.withLock { states[id] = state; brokers[id] = broker; opening.add(id) }
        persist()
        log.publish(r.agentId, id, AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
        try {
            val session = withTimeout(30_000) { requireNotNull(factory).open(r.connection, r.registration, state, broker, scope) }
            val retained = lock.withLock {
                if (states[id] === state) { live[id] = session; true } else false
            }
            if (!retained) { session.close(); error("Execution connection ended during startup") }
            check(state.snapshot().phase != ExecutionPhase.Lost) { "Execution connection ended during startup" }
            r = r.copy(support = session.capabilities); persist()
            registry.negotiated(r.connection.id, session.capabilities)
            readinessChanged()
        } catch (e: Throwable) { withContext(NonCancellable) { broker.lost(); discard(id) }; throw e }
        finally { lock.withLock { opening.remove(id) } }
    }
    override suspend fun send(params: SendParams): SendResult = send(params, null)

    internal suspend fun send(p: SendParams, report: SessionEntry.ToolReport?, onBegin: suspend (SessionId) -> Unit = {}): SendResult {
        require(p.delivery == SendDelivery.Turn) { "This external harness does not support Queue or Steer" }
        require(p.text.isNotBlank() || p.attachments.isNotEmpty())
        require(p.attachments.size <= MAX_ATTACHMENTS && p.attachments.distinct().size == p.attachments.size)
        val attachments = p.attachments.map(resolve)
        require(attachments.sumOf { it.attachment.size } <= MAX_ATTACHMENT_TOTAL_BYTES)
        val submission = p.submissionId ?: Uuid.random().toString()
        val retry = lock.withLock { records.values.firstOrNull { it.agentId == p.agentId && submission in it.submissions }?.sessionId }
        val id = p.sessionId ?: retry ?: create(p.agentId).id
        session(p.agentId, id)
        val r = record(id)
        if (submission in r.submissions) {
            val prior = log.entries(p.agentId, id).filterIsInstance<SessionEntry.UserMessage>().firstOrNull { it.submissionId == submission }
            require(prior == null || prior.copy(writtenAt = null) == SessionEntry.UserMessage(p.text, attachments = attachments.map { it.attachment }, voice = p.voice, submissionId = submission, action = p.action)) { "Submission identity reused with different content" }
            return SendResult(id, RunId(submission), p.text)
        }
        check(!r.archived && !r.closed && !r.lost && r.failure == null)
        attachments.forEach { require(it.attachment.mimeType in r.support.supportedMimeTypes) { "Unsupported attachment type" } }
        lock.withLock { turns[id]?.takeIf { it.finishing } }?.done?.await()
        val turn = Turn()
        val (session, state) = lock.withLock {
            check(id !in turns) { "A run is already active in this session" }
            (requireNotNull(live[id]) to states.getValue(id)).also { turns[id] = turn }
        }
        try {
            onBegin(id)
            state.begin()
            state.boundSink().publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
            val input = report ?: SessionEntry.UserMessage(p.text, attachments = attachments.map { it.attachment }, voice = p.voice, submissionId = submission, action = p.action)
            mutate(id) { current -> current.copy(submissions = current.submissions + (submission to null), inputCheckpoint = input as? SessionEntry.UserMessage, reportCheckpoint = report) }
            log.appendAll(p.agentId, id, listOf(input))
            mutate(id) { it.copy(inputCheckpoint = null, reportCheckpoint = null) }
            if (lock.withLock { turn.cancelled }) {
                turn.submitted.complete(false)
                state.boundSink().publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
                return SendResult(id, RunId(submission), p.text)
            }
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val accepted = session.prompt(ExecutionInput(if (p.voice) "$liveVoiceInstructions\n\n${p.text}" else p.text, attachments, p.action))
                    if (lock.withLock { states[id] === state }) mutate(id) { current -> current.copy(submissions = current.submissions + (submission to accepted.messageId), acceptedSubmissions = current.acceptedSubmissions + submission) }
                } catch (e: Exception) {
                    withContext(NonCancellable) { lock.withLock { brokers[id]?.takeIf { states[id] === state && turns[id] === turn } }?.lost() }
                }
            }
            lock.withLock { if (states[id] === state && turns[id] === turn) dispatch[id] = job }
            turn.submitted.complete(true)
        } catch (e: Throwable) {
            withContext(NonCancellable) { release(id, turn) }
            throw e
        }
        return SendResult(id, RunId(submission), p.text)
    }

    override suspend fun stop(sessionId: SessionId) {
        val (session, turn) = lock.withLock {
            val turn = turns[sessionId]
            if (turn?.finishing == true) return
            turn?.cancelled = true
            live[sessionId] to turn
        }
        if (turn == null || turn.submitted.await()) session?.cancel()
    }
    suspend fun closeExecution(id: SessionId) { discard(id); sealInterrupted(id); mutate(id) { it.copy(closed = true, lost = true) }; readinessChanged() }
    private suspend fun sealInterrupted(id: SessionId) {
        val r = record(id)
        if (r.draft.isNotEmpty()) {
            log.appendAll(r.agentId, id, listOf(SessionEntry.Notice("interrupted:${r.generation}", "execution_interrupted", "Execution ended before this output could be confirmed.", r.draft)))
            mutate(id) { it.copy(draft = emptyList()) }
        }
    }
    suspend fun discard(id: SessionId) = withContext(NonCancellable) {
        val (broker, session, job) = lock.withLock {
            states.remove(id)
            Triple(brokers.remove(id), live.remove(id), dispatch.remove(id))
        }
        try { broker?.lost() } finally {
            job?.cancel()
            session?.close()
        }
    }
    private suspend fun releaseLost(id: SessionId, generation: String) {
        val pair = lock.withLock {
            if (states[id]?.generation != generation) return
            states.remove(id); brokers.remove(id); live.remove(id) to dispatch.remove(id)
        }
        pair.second?.cancel()
        pair.first?.close()
    }
    suspend fun delete(agentId: AgentId, id: SessionId) {
        if (!owns(id)) return
        require(record(id).agentId == agentId)
        discard(id)
        join(id)
        recovery.delete(agentId, id)
        lock.withLock { records.remove(id) }
    }
    override suspend fun answer(answer: RespondParams): Boolean = lock.withLock { live[answer.sessionId] }?.respond(answer.requestId, answer.answer)
        ?: (lock.withLock { brokers[answer.sessionId] }?.answer(answer.requestId, answer.answer.toHarnessJson()) ?: false)
    suspend fun close() { lock.withLock { states.keys.toList() }.forEach { discard(it) } }
}
