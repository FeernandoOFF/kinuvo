package kinuvo.harness.server.execution

import kinuvo.execution.api.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.harness.core.store.SessionCommitConflict
import kinuvo.harness.server.SessionLog
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

@Serializable
data class ExternalSession(val agentId: String, val connectionId: String, val snapshot: ExecutionSnapshot)

class ExecutionCoordinator(
    val registry: ExecutionRegistry,
    private val factory: ExecutionFactory?,
    private val scope: CoroutineScope,
    private val log: SessionLog,
    private val recovery: ExecutionRecoveryStore = MemoryExecutionRecovery(),
    private val resolve: (String) -> AttachmentData = { error("Attachments unavailable") },
    private val readinessChanged: suspend () -> Unit = {},
) : ExecutionDispatch {
    private val lock = Mutex()
    private val journal = Mutex()
    private val records = recovery.load().associateBy { it.sessionId }.toMutableMap()
    private val live = mutableMapOf<String, ExecutionSession>()
    private val states = mutableMapOf<String, ExecutionState>()
    private val brokers = mutableMapOf<String, ExecutionBroker>()
    private val dispatch = mutableMapOf<String, Job>()
    private val opening = mutableSetOf<String>()

    suspend fun reload() { val loaded = recovery.load(); lock.withLock { records.clear(); records.putAll(loaded.associateBy { it.sessionId }) } }

    suspend fun recover() {
        for (saved in records.values.toList()) {
            try {
                var r = saved
                r.inputCheckpoint?.let { log.appendAll(r.agentId, r.sessionId, listOf(it)) }
                for (submission in r.submissions.keys - r.acceptedSubmissions) {
                    log.appendAll(r.agentId, r.sessionId, listOf(SessionEntry.Notice("submission-uncertain:$submission", "submission_uncertain", "The harness did not confirm this submission before the connection ended. It was not resent.")))
                }
                r = r.copy(inputCheckpoint = null)
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
                KotlinLogging.logger("kinuvo.execution").error { "Recovery failed for ${saved.agentId}/${saved.sessionId}: ${e.message}" }
            }
        }
    }

    private suspend fun save(record: ExecutionRecovery) = journal.withLock {
        recovery.save(record)
        lock.withLock { records[record.sessionId] = record }
    }
    private suspend fun mutate(id: String, change: (ExecutionRecovery) -> ExecutionRecovery): ExecutionRecovery = journal.withLock {
        val next = change(lock.withLock { records.getValue(id) })
        recovery.save(next)
        lock.withLock { records[id] = next }
        next
    }
    suspend fun currentStateOwner(state: ExecutionState): String = lock.withLock {
        states.entries.firstOrNull { it.value === state }?.let { records.getValue(it.key).agentId }
            ?: error("Stale execution generation")
    }
    suspend fun owns(id: String) = lock.withLock { id in records }
    suspend fun sessionAgent(id: String) = lock.withLock { records.getValue(id).agentId }
    suspend fun inUse(connectionId: String) = lock.withLock { records.values.any { it.connection.id == connectionId && (it.sessionId in live || it.sessionId in opening) } }
    suspend fun agentInUse(agentId: String) = lock.withLock { records.values.any { it.agentId == agentId && (it.sessionId in live || it.sessionId in opening) } }
    suspend fun record(id: String) = lock.withLock { records.getValue(id) }
    suspend fun archives() = lock.withLock { records.values.filter { it.archived }.map { it.registration }.distinctBy { it.id } }
    suspend fun restore(agentId: String) { lock.withLock { records.values.filter { it.agentId == agentId && it.archived } }.forEach { saved -> mutate(saved.sessionId) { it.copy(archived = false) } } }
    suspend fun archive(agentId: String) { lock.withLock { records.values.filter { it.agentId == agentId } }.forEach { save(it.copy(archived = true)) } }
    suspend fun session(agentId: String, id: String): SessionInfo {
        val r = record(id); require(r.agentId == agentId)
        return log.session(agentId, id).copy(legacyOrder = r.legacyOrder)
    }
    suspend fun snapshot(id: String): ExecutionSnapshot {
        val r = record(id)
        return log.executionSnapshot(r.agentId, id).copy(externalSessionId = r.externalSessionId).let { if (r.lost || r.closed) it.copy(phase = ExecutionPhase.Lost) else it }
    }
    override suspend fun snapshot(agentId: String, sessionId: String): ExecutionSnapshot { session(agentId, sessionId); return snapshot(sessionId) }
    override suspend fun create(agentId: String): SessionInfo {
        val config = registry.configured(agentId)
        val registration = requireNotNull(registry.registration(agentId))
        require(Regex("[a-z0-9][a-z0-9-]{0,31}").matches(agentId))
        val info = log.createSession(agentId, "")
        save(ExecutionRecovery(agentId, info.id, config, registration, Uuid.random().toString()))
        open(record(info.id))
        return session(agentId, info.id)
    }
    suspend fun resume(id: String): ExecutionSnapshot {
        val old = record(id)
        check(old.lost && !old.closed && !old.archived) { "This Session cannot resume" }
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
        suspend fun persist() { r = mutate(id) { r.copy(submissions = it.submissions, acceptedSubmissions = it.acceptedSubmissions, inputCheckpoint = it.inputCheckpoint, archived = it.archived, closed = it.closed) }; written = TimeSource.Monotonic.markNow() }
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
                if (event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Lost) scope.launch { releaseLost(id, initial.generation); readinessChanged() }
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
    override suspend fun send(p: SendParams): SendResult {
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
            require(prior == null || prior == SessionEntry.UserMessage(p.text, attachments = attachments.map { it.attachment }, voice = p.voice, submissionId = submission)) { "Submission identity reused with different content" }
            return SendResult(id, submission, p.text)
        }
        check(!r.archived && !r.closed && !r.lost && r.failure == null)
        attachments.forEach { require(it.attachment.mimeType in r.support.supportedMimeTypes) { "Unsupported attachment type" } }
        val (session, state) = lock.withLock { requireNotNull(live[id]) to states.getValue(id) }
        state.begin()
        state.boundSink().publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
        val input = SessionEntry.UserMessage(p.text, attachments = attachments.map { it.attachment }, voice = p.voice, submissionId = submission)
        mutate(id) { current -> current.copy(submissions = current.submissions + (submission to null), inputCheckpoint = input) }
        log.appendAll(p.agentId, id, listOf(input))
        mutate(id) { it.copy(inputCheckpoint = null) }
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val accepted = session.prompt(ExecutionInput(p.text, attachments))
                if (lock.withLock { states[id] === state }) mutate(id) { current -> current.copy(submissions = current.submissions + (submission to accepted.messageId), acceptedSubmissions = current.acceptedSubmissions + submission) }
            } catch (e: Exception) {
                withContext(NonCancellable) { if (lock.withLock { states[id] === state }) lock.withLock { brokers[id] }?.lost() }
            }
        }
        lock.withLock { dispatch[id] = job }
        return SendResult(id, submission, p.text)
    }
    override suspend fun stop(id: String) { lock.withLock { live[id] }?.cancel() }
    suspend fun closeExecution(id: String) { discard(id); sealInterrupted(id); mutate(id) { it.copy(closed = true, lost = true) }; readinessChanged() }
    private suspend fun sealInterrupted(id: String) {
        val r = record(id)
        if (r.draft.isNotEmpty()) {
            log.appendAll(r.agentId, id, listOf(SessionEntry.Notice("interrupted:${r.generation}", "execution_interrupted", "Execution ended before this output could be confirmed.", r.draft)))
            mutate(id) { it.copy(draft = emptyList()) }
        }
    }
    suspend fun discard(id: String) = withContext(NonCancellable) {
        val broker = lock.withLock { brokers[id] }
        broker?.lost()
        val pair = lock.withLock { states.remove(id); brokers.remove(id); live.remove(id) to dispatch.remove(id) }
        pair.second?.cancel()
        pair.first?.close()
    }
    private suspend fun releaseLost(id: String, generation: String) {
        val pair = lock.withLock {
            if (states[id]?.generation != generation) return
            states.remove(id); brokers.remove(id); live.remove(id) to dispatch.remove(id)
        }
        pair.second?.cancel()
        pair.first?.close()
    }
    suspend fun delete(agentId: String, id: String) {
        if (!owns(id)) return
        require(record(id).agentId == agentId)
        discard(id); recovery.delete(agentId, id); lock.withLock { records.remove(id) }
    }
    override suspend fun answer(p: ExecutionAnswer): Boolean = lock.withLock { live[p.sessionId] }?.respond(p.requestId, p.answer)
        ?: (lock.withLock { brokers[p.sessionId] }?.answer(p.requestId, p.answer) ?: false)
    suspend fun close() { lock.withLock { states.keys.toList() }.forEach { discard(it) } }
}
internal fun toolEntries(message: ExecutionMessage): List<SessionEntry> {
    val payload = message.payload as? JsonObject
    val native = payload?.takeIf { "type" in it }?.let {
        runCatching { ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), it) }.getOrNull()
    }
    if (native is SessionEntry.ToolCall || native is SessionEntry.ToolResult) return listOf(native)
    val id = (payload?.get("toolCallId") as? JsonPrimitive)?.content ?: message.id
    val input = payload?.get("rawInput")?.toString() ?: ""
    val call = SessionEntry.ToolCall(id, message.text, input)
    val status = (payload?.get("status") as? JsonPrimitive)?.content
    if (status != "completed" && status != "failed") return listOf(call)
    val output = payload?.get("rawOutput")?.takeUnless { it is JsonNull } ?: payload?.get("content")?.takeUnless { it is JsonNull }
    return listOf(call, SessionEntry.ToolResult(id, message.text, output?.let { (it as? JsonPrimitive)?.content ?: it.toString() } ?: "", isError = status == "failed"))
}

internal fun transcript(snapshot: ExecutionSnapshot): List<SessionEntry> = snapshot.messages.flatMap { m ->
    val native = m.payload?.let { runCatching { ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), it) }.getOrNull() }
    when {
        native is SessionEntry.UserMessage -> listOf(native)
        native is SessionEntry.AssistantMessage -> listOf(native.copy(messageId = native.messageId ?: m.id))
        native is SessionEntry.Reasoning -> listOf(native.copy(messageId = native.messageId ?: m.id))
        native is SessionEntry.Notice -> listOf(native)
        m.role == "user" -> listOf(SessionEntry.UserMessage(m.text))
        m.role == "tool" -> toolEntries(m)
        m.role == "reasoning" -> listOf(SessionEntry.Reasoning(m.text, messageId = m.id))
        else -> listOf(SessionEntry.AssistantMessage(m.text, m.id))
    }
}
