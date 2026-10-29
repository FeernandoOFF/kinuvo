package kinuvo.harness.server.execution

import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

@Serializable
data class ExternalSession(val agentId: String, val connectionId: String, val snapshot: ExecutionSnapshot)

class ExecutionCoordinator(
    val registry: ExecutionRegistry,
    private val factory: ExecutionFactory?,
    private val scope: CoroutineScope,
    restored: List<ExternalSession> = emptyList(),
    private val persist: (List<ExternalSession>) -> Unit = {},
    private val readinessChanged: suspend () -> Unit = {},
    private val changed: (ExecutionSnapshot) -> Unit = {},
) : ExecutionDispatch {
    private val lock = Mutex()
    private val commands = Mutex()
    private val records = restored.associateBy { it.snapshot.sessionId }.mapValues { (_, v) -> v.copy(snapshot = v.snapshot.copy(phase = ExecutionPhase.Lost, pending = emptyList())) }.toMutableMap()
    private val opening = mutableSetOf<String>()
    private val live = mutableMapOf<String, ExecutionSession>()
    private val states = mutableMapOf<String, ExecutionState>()
    private class Dispatch { lateinit var job: Job; var started = false }
    private val dispatch = mutableMapOf<String, Dispatch>()
    private val brokers = mutableMapOf<String, ExecutionBroker>()

    suspend fun inUse(connectionId: String) = lock.withLock { records.values.any { it.connectionId == connectionId && (it.snapshot.sessionId in live || it.snapshot.sessionId in opening) } }
    suspend fun agentInUse(agentId: String) = lock.withLock { records.values.any { it.agentId == agentId && (it.snapshot.sessionId in live || it.snapshot.sessionId in opening) } }
    suspend fun owns(id: String) = lock.withLock { id in records }
    suspend fun snapshot(id: String) = lock.withLock { records.getValue(id).snapshot }
    override suspend fun snapshot(agentId: String, sessionId: String): ExecutionSnapshot {
        session(agentId, sessionId)
        return snapshot(sessionId)
    }
    suspend fun sessionAgent(id: String) = lock.withLock { records.getValue(id).agentId }
    suspend fun sessions(agentId: String) = lock.withLock { records.values.filter { it.agentId == agentId }.map { info(it) }.reversed() }
    suspend fun session(agentId: String, id: String): SessionInfo = lock.withLock { val r = records.getValue(id); require(r.agentId == agentId); info(r) }
    private fun info(r: ExternalSession) = SessionInfo(r.snapshot.sessionId, r.agentId, r.snapshot.messages.firstOrNull { it.role == "user" }?.text?.take(100) ?: "New Session", when(r.snapshot.phase) { ExecutionPhase.Running -> SessionStatus.Running; ExecutionPhase.RequiresAction -> SessionStatus.Waiting; else -> SessionStatus.Idle })
    suspend fun history(agentId: String, id: String): List<SessionChange.Entry> {
        session(agentId, id)
        return transcript(snapshot(id)).mapIndexed { index, entry -> SessionChange.Entry(index, entry) }
    }
    override suspend fun create(agentId: String): SessionInfo = commands.withLock { open(agentId, Uuid.random().toString()); }

    suspend fun resume(id: String): ExecutionSnapshot = commands.withLock {
        val record = lock.withLock { records.getValue(id) }
        check(record.snapshot.phase == ExecutionPhase.Lost) { "Close the existing execution before resuming" }
        requireNotNull(record.snapshot.externalSessionId) { "No stored external Session ID to resume" }
        check(registry.configured(record.agentId).connection is ExecutionConnection.Acp) { "Resume is only supported for ACP Sessions on this host" }
        release(id)
        open(record.agentId, id, record.snapshot)
        snapshot(id)
    }

    private suspend fun open(agentId: String, id: String, previous: ExecutionSnapshot? = null): SessionInfo {
        val config = registry.configured(agentId)
        lateinit var state: ExecutionState
        state = ExecutionState(id, { snapshot ->
            val current = lock.withLock {
                if (states[id] !== state) false else {
                    records[id] = ExternalSession(agentId, config.id, snapshot)
                    persist(records.values.toList())
                    changed(snapshot)
                    true
                }
            }
            if (current && snapshot.phase == ExecutionPhase.Lost) scope.launch { release(id, state) }
        }, previous?.copy(phase = ExecutionPhase.Idle, pending = emptyList(), stopReason = null) ?: ExecutionSnapshot(id))
        val broker = ExecutionBroker(state)
        lock.withLock {
            states[id] = state; brokers[id] = broker; opening.add(id)
            records[id] = ExternalSession(agentId, config.id, state.snapshot())
            persist(records.values.toList())
        }
        try {
            val session = withTimeout(30_000) { requireNotNull(factory) { "External execution is unavailable on this host" }.open(config, requireNotNull(registry.registration(agentId)), state, broker, scope) }
            val retained = lock.withLock { if (states[id] === state) { live[id] = session; true } else false }
            if (!retained) {
                session.close()
                error("Execution connection was lost during startup")
            }
            check(state.snapshot().phase != ExecutionPhase.Lost) { "Execution connection was lost during startup" }
            registry.negotiated(config.id, session.capabilities)
            readinessChanged()
        } catch (e: Throwable) {
            withContext(NonCancellable) { broker.lost(); release(id, state) }
            throw e
        } finally { lock.withLock { opening.remove(id) } }
        return session(agentId, id)
    }

    suspend fun closeExecution(id: String) = commands.withLock {
        check(owns(id)) { "Unknown external Session" }
        val (turn, session, broker) = lock.withLock { Triple(dispatch.remove(id), live[id], brokers[id]) }
        turn?.job?.cancelAndJoin()
        withContext(NonCancellable) {
            runCatching { withTimeout(2_000) { session?.cancel() } }
            broker?.lost()
            release(id)
        }
    }
    override suspend fun send(p: SendParams): SendResult = commands.withLock {
        require(p.attachments.isEmpty()) { "External attachments are not supported yet" }
        require(p.text.isNotBlank())
        val id = p.sessionId ?: open(p.agentId, Uuid.random().toString()).id
        session(p.agentId, id)
        registry.route(p.agentId)
        val (session, state, broker) = lock.withLock { Triple(requireNotNull(live[id]) { "Execution connection was lost; create a new Session" }, states.getValue(id), brokers.getValue(id)) }
        state.begin()
        state.message(ExecutionMessage("local:" + Uuid.random().toString(), "user", p.text))
        val turn = Dispatch()
        turn.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                lock.withLock { turn.started = true }
                coroutineScope {
                    val acknowledgement = async { session.prompt(p.text) }
                    while (withTimeoutOrNull(30_000) { acknowledgement.await(); true } != true) {
                        check(state.snapshot().pending.isNotEmpty()) { "Prompt acknowledgement timed out" }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException || e is TimeoutCancellationException)
                    withContext(NonCancellable) { broker.lost(); release(id, state) }
            }
        }
        lock.withLock { dispatch[id] = turn }
        turn.job.start()
        return SendResult(id, Uuid.random().toString(), p.text)
    }
    override suspend fun stop(id: String): Unit = commands.withLock {
        val (turn, session, state) = lock.withLock { Triple(dispatch.remove(id), live[id], states[id]) }
        turn?.job?.cancelAndJoin()
        if (turn != null && !turn.started) state?.update(ExecutionPhase.Idle, "cancelled")
        else session?.cancel()
        Unit
    }
    suspend fun discard(id: String) {
        withContext(NonCancellable) { lock.withLock { brokers[id] }?.lost(); release(id) }
    }
    private suspend fun release(id: String, expected: ExecutionState? = null) = withContext(NonCancellable) {
        val session = lock.withLock {
            if (expected != null && states[id] !== expected) return@withLock null
            states.remove(id); brokers.remove(id); dispatch.remove(id)?.job?.cancel(); live.remove(id)
        }
        session?.close()
    }
    override suspend fun answer(p: ExecutionAnswer): Boolean {
        val session = lock.withLock { live[p.sessionId] }
        return session?.respond(p.requestId, p.answer) ?: (lock.withLock { brokers[p.sessionId] }?.answer(p.requestId, p.answer) ?: false)
    }
    suspend fun close() { lock.withLock { live.values.toList().also { live.clear() } }.forEach { runCatching { it.close() } } }
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
    when (m.role) {
        "user" -> listOf(SessionEntry.UserMessage(m.text))
        "tool" -> toolEntries(m)
        "reasoning" -> listOf(SessionEntry.Reasoning(m.text, messageId = m.id))
        else -> listOf(SessionEntry.AssistantMessage(m.text, m.id))
    }
}
