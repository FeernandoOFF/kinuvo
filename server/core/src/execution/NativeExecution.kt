package kinuvo.server.execution

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.AgentError
import kinuvo.server.ServerError
import kinuvo.server.SessionError
import kinuvo.server.throwing
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.harness.Agent
import kinuvo.harness.ExecutionDispatch
import kinuvo.harness.ExecutionInput
import kinuvo.harness.RunKind
import kinuvo.harness.ToolReports
import kinuvo.server.AgentRuntime
import kinuvo.server.Runs
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger("kinuvo.server")

/** Runs the Server's own Agents: each turn is a [Runs] run that opens a Session of the built-in harness. */
class NativeExecution(
    private val harness: AgentRuntime,
    private val runs: Runs,
    private val scope: CoroutineScope,
    private val agent: suspend context(Raise<AgentError>) (AgentId) -> Agent,
    private val capabilities: () -> ServerCapabilities,
    private val ran: (Agent, SessionId) -> Unit,
    private val reported: suspend (sessionId: SessionId, text: String) -> Unit,
) : ExecutionDispatch {
    private data class Pending(val agent: Agent, val session: SessionId, val input: ExecutionInput, val message: QueuedMessage, val onEvent: suspend (AgentEvent) -> Unit, val userTurn: Boolean, val unsteered: (suspend () -> Unit)? = null)
    private val deliveryLock = Mutex()
    private val queued = mutableMapOf<SessionId, MutableList<Pending>>()
    private val steering = mutableMapOf<SessionId, MutableList<Pending>>()
    private val childReports = mutableMapOf<SessionId, Pair<Agent, suspend () -> SessionEntry.ToolReport?>>()
    private val paused = mutableSetOf<SessionId>()
    private val pumping = mutableSetOf<SessionId>()

    private suspend fun publishQueue(agent: Agent, session: SessionId) {
        harness.log.publish(agent.id, session, AgentEvent.QueueChanged(queued[session].orEmpty().map { it.message }))
    }

    suspend fun cancelQueued(p: CancelQueuedParams) = deliveryLock.withLock {
        require(owner(p.sessionId) == p.agentId) { "Session does not belong to this Agent" }
        queued[p.sessionId]?.removeAll { it.message.id == p.id }
        harness.log.publish(p.agentId, p.sessionId, AgentEvent.QueueChanged(queued[p.sessionId].orEmpty().map { it.message }))
    }

    private fun pump(session: SessionId) {
        if (!pumping.add(session)) return
        scope.launch {
            try {
                while (true) {
                    runs.join(session)
                    val started = deliveryLock.withLock {
                        if (runs.isActive(session)) return@withLock true
                        if (session in paused) return@withLock false
                        childReports.remove(session)?.let { (agent, take) ->
                            val report = take()
                            if (report != null) {
                                startReport(agent, session, harness.uiCatalogs.forRun(), wake = report)
                                return@withLock true
                            }
                        }
                        val next = queued[session]?.removeFirstOrNull() ?: return@withLock false
                        publishQueue(next.agent, session)
                        try { start(next) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { harness.log.publish(next.agent.id, session, AgentEvent.RunError(requireNotNull(next.input.runId), e.message ?: "Queued message failed")) }
                        true
                    }
                    if (!started) break
                }
            } finally {
                deliveryLock.withLock {
                    pumping.remove(session)
                    if (session !in paused && (session in childReports || queued[session].orEmpty().isNotEmpty())) pump(session)
                }
            }
        }
    }

    private suspend fun start(p: Pending, wake: SessionEntry.ToolReport? = null): Runs.Run {
        check(!runs.isActive(p.session)) { "A run is already active in this session" }
        steering[p.session] = mutableListOf()
        try {
            return runs.start(p.agent, p.session, p.input.copy(steering = {
                deliveryLock.withLock {
                    steering[p.session].orEmpty().map { pending ->
                        val kind = pending.input.kind as RunKind.Chat
                        SessionEntry.UserMessage(pending.input.text, null, kind.attachments, kind.voice, action = pending.input.action, inputs = pending.input.inputs)
                    }.also { steering[p.session]?.clear() } + listOfNotNull(childReports.remove(p.session)?.second?.invoke())
                }
            }), userTurn = p.userTurn, wake = wake != null, reserved = {
                wake?.let { harness.log.append(p.agent.id, p.session, it) }
            }, onEvent = p.onEvent, onDone = { stopped ->
                deliveryLock.withLock {
                    val remaining = steering.remove(p.session).orEmpty()
                    val messages = remaining.filter { it.unsteered == null }
                    if (messages.isNotEmpty()) queued.getOrPut(p.session) { mutableListOf() }.addAll(0, messages)
                    if (!stopped) remaining.mapNotNull { it.unsteered }.forEach { fallback ->
                        scope.launch {
                            try { fallback() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { log.warn(e) { "Sub-agent follow-up could not queue in Session ${p.session}" } }
                        }
                    }
                    if (stopped) {
                        paused += p.session
                        childReports.remove(p.session)
                    }
                    publishQueue(p.agent, p.session)
                    pump(p.session)
                }
                ran(p.agent, p.session)
            })
        } catch (e: Exception) { steering.remove(p.session); throw e }
    }

    context(_: Raise<AgentError>)
    suspend fun open(agentId: AgentId): SessionInfo = harness.newSession(agent(agentId))

    override suspend fun create(agentId: AgentId): SessionInfo = throwing<AgentError, _> { open(agentId) }

    override suspend fun snapshot(agentId: AgentId, sessionId: SessionId): ExecutionSnapshot = throwing<AgentError, _> { snapshotOf(agentId, sessionId) }

    context(_: Raise<AgentError>)
    suspend fun snapshotOf(agentId: AgentId, sessionId: SessionId): ExecutionSnapshot {
        val snapshot = harness.log.executionSnapshot(agent(agentId), sessionId)
        return if (snapshot.phase == ExecutionPhase.Idle && runs.isActive(sessionId)) snapshot.copy(phase = ExecutionPhase.Running) else snapshot
    }

    suspend fun owner(sessionId: SessionId) = harness.store.agents().firstOrNull { a -> harness.store.sessions(a.id).any { it.id == sessionId } }?.id

    override suspend fun answer(answer: RespondParams): Boolean {
        owner(answer.sessionId) ?: return false
        val pending = harness.log.pending(answer.sessionId).firstOrNull { it.id == answer.requestId } ?: return false
        return harness.respondToTool(pending, answer.answer)
    }

    override suspend fun send(params: SendParams): SendResult = throwing<ServerError, _> { send(params, null) {} }

    context(_: Raise<AgentError>, _: Raise<SessionError>)
    suspend fun send(p: SendParams, requested: List<UiCatalog>?, userTurn: Boolean = true, onEvent: suspend (AgentEvent) -> Unit): SendResult =
        requireNotNull(send(p, requested, onEvent, userTurn, unsteered = null))

    suspend fun trySteer(p: SendParams, allowed: suspend () -> Boolean, fallback: suspend () -> Unit): Boolean = throwing<ServerError, _> {
        send(p.copy(delivery = SendDelivery.Steer), null, {}, false, unsteered = fallback, steerAllowed = allowed) != null
    }

    context(_: Raise<AgentError>, _: Raise<SessionError>)
    private suspend fun send(p: SendParams, requested: List<UiCatalog>?, onEvent: suspend (AgentEvent) -> Unit, userTurn: Boolean, unsteered: (suspend () -> Unit)?, steerAllowed: suspend () -> Boolean = { true }): SendResult? {
        val catalogs = requested ?: harness.uiCatalogs.forRun()
        val agent = agent(p.agentId)
        require(p.text.isNotBlank() || p.attachments.isNotEmpty()) { "Add a message or attachment" }
        check(p.attachments.isEmpty() || ServerFeature.Attachments in capabilities().features) { "Attachments are not available on this Server" }
        require(p.attachments.size <= MAX_ATTACHMENTS && p.attachments.distinct().size == p.attachments.size) { "Attach up to 4 different files" }
        val attachments = withContext(Dispatchers.Default) { p.attachments.map(harness.attachments::get) }
        require(attachments.sumOf { it.size } <= MAX_ATTACHMENT_TOTAL_BYTES) { "Attachments must total 20 MB or less" }
        val sessionId = p.sessionId?.also { id ->
            if (harness.store.sessions(agent.id).none { it.id == id }) raise(SessionError.NotOfAgent(id, agent.id))
        } ?: harness.newSession(agent).id
        val effective = harness.composer.capabilities(agent, sessionId, null, capabilities())
        effective.chat.requireReady()
        attachments.forEach { attachment -> effective.attachment(attachment.mimeType).requireReady() }
        val (activation, message) = (if (ServerFeature.Skills in capabilities().features) harness.slashActivation(agent, p.text) else null) ?: (null to p.text)
        val kind = RunKind.Chat(activation, attachments, p.voice, discovery = p.discovery && !p.voice && p.action == null && p.delivery == SendDelivery.Turn, catalogs = catalogs, reports = toolReports(agent, sessionId, catalogs))
        return deliveryLock.withLock {
            if (unsteered != null && (sessionId in paused || sessionId !in steering || !runs.isActive(sessionId) || !steerAllowed())) return@withLock null
            val id = RunId(Uuid.random().toString())
            val pending = Pending(agent, sessionId, ExecutionInput(message, action = p.action, kind = kind, runId = id), QueuedMessage(id.value, message, attachments), onEvent, userTurn, unsteered)
            when {
                p.delivery == SendDelivery.Steer && runs.isActive(sessionId) -> {
                    val inbox = steering[sessionId] ?: error("This run cannot take steering input")
                    require(activation == null) { "Skill commands cannot steer a running turn" }
                    harness.log.updateDetails(agent.id, sessionId, settled = false)
                    inbox += pending
                }
                p.delivery == SendDelivery.Queue -> {
                    queued.getOrPut(sessionId) { mutableListOf() } += pending
                    publishQueue(agent, sessionId)
                    pump(sessionId)
                }
                else -> {
                    paused -= sessionId
                    start(pending)
                }
            }
            SendResult(sessionId, id, message, activation)
        }
    }

    suspend fun report(agentId: AgentId, sessionId: SessionId, take: suspend () -> SessionEntry.ToolReport?) {
        val agent = throwing<AgentError, _> { agent(agentId) }
        deliveryLock.withLock {
            if (sessionId in paused) return
            childReports[sessionId] = agent to take
            pump(sessionId)
        }
    }

    private suspend fun startReport(agent: Agent, sessionId: SessionId, catalogs: List<UiCatalog>, report: SessionEntry.ToolReport? = null, wake: SessionEntry.ToolReport? = null) {
        try {
            val kind = RunKind.Chat(report = report, catalogs = catalogs, reports = toolReports(agent, sessionId, catalogs))
            if (report != null) {
                runs.start(agent, sessionId, ExecutionInput("", kind = kind), queued = true, onEvent = { event ->
                    if (event is AgentEvent.TextMessageEnd) reported(sessionId, event.text)
                })
            } else {
                val input = ExecutionInput("", kind = kind)
                start(Pending(agent, sessionId, input, QueuedMessage(Uuid.random().toString(), ""), { event ->
                    if (event is AgentEvent.TextMessageEnd) reported(sessionId, event.text)
                }, userTurn = false), wake)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.warn(e) { "Background report could not start in Session $sessionId" } }
    }

    private fun toolReports(agent: Agent, sessionId: SessionId, catalogs: List<UiCatalog>): ToolReports =
        ToolReports(scope) { startReport(agent, sessionId, catalogs, it) }

    override suspend fun stop(sessionId: SessionId) {
        deliveryLock.withLock {
            paused += sessionId
            childReports.remove(sessionId)
            runs.stop(sessionId)
        }
    }
}
