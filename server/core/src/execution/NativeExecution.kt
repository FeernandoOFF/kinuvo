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
import kinuvo.server.HarnessRuntime
import kinuvo.server.Runs
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger("kinuvo.server")

/** Runs the Server's own Agents: each turn is a [Runs] run that opens a Session of the built-in harness. */
class NativeExecution(
    private val harness: HarnessRuntime,
    private val runs: Runs,
    private val scope: CoroutineScope,
    private val agent: suspend context(Raise<AgentError>) (AgentId) -> Agent,
    private val capabilities: () -> ServerCapabilities,
    private val ran: (Agent, SessionId) -> Unit,
    private val reported: suspend (sessionId: SessionId, text: String) -> Unit,
) : ExecutionDispatch {
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
    suspend fun send(p: SendParams, requested: List<UiCatalog>?, onEvent: suspend (AgentEvent) -> Unit): SendResult {
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
        val effective = harness.capabilities(agent, sessionId, null, capabilities())
        effective.chat.requireReady()
        attachments.forEach { attachment -> effective.attachment(attachment.mimeType).requireReady() }
        val (activation, message) = (if (ServerFeature.Skills in capabilities().features) harness.slashActivation(agent, p.text) else null) ?: (null to p.text)
        val kind = RunKind.Chat(activation, attachments, p.voice, catalogs = catalogs, reports = toolReports(agent, sessionId, catalogs))
        val run = runs.start(agent, sessionId, ExecutionInput(message, action = p.action, kind = kind), onEvent = onEvent, onDone = { ran(agent, sessionId) })
        return SendResult(sessionId, run.id, message, activation)
    }

    private fun toolReports(agent: Agent, sessionId: SessionId, catalogs: List<UiCatalog>): ToolReports = ToolReports(scope) { report ->
        try {
            val kind = RunKind.Chat(report = report, catalogs = catalogs, reports = toolReports(agent, sessionId, catalogs))
            runs.start(agent, sessionId, ExecutionInput("", kind = kind), queued = true, onEvent = { event ->
                if (event is AgentEvent.TextMessageEnd) reported(sessionId, event.text)
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.warn(e) { "Background report could not start in Session $sessionId" } }
    }

    override suspend fun stop(sessionId: SessionId) { runs.stop(sessionId) }
}
