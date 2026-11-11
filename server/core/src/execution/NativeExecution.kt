package kinuvo.server.execution

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
    private val agent: suspend (String) -> Agent,
    private val capabilities: () -> ServerCapabilities,
    private val ran: (Agent, String) -> Unit,
    private val reported: suspend (sessionId: String, text: String) -> Unit,
) : ExecutionDispatch {
    override suspend fun create(agentId: String): SessionInfo = harness.newSession(agent(agentId))

    override suspend fun snapshot(agentId: String, sessionId: String): ExecutionSnapshot {
        val snapshot = harness.log.executionSnapshot(agent(agentId), sessionId)
        return if (snapshot.phase == ExecutionPhase.Idle && runs.isActive(sessionId)) snapshot.copy(phase = ExecutionPhase.Running) else snapshot
    }

    suspend fun owner(sessionId: String) = harness.store.agents().firstOrNull { a -> harness.store.sessions(a.id).any { it.id == sessionId } }?.id

    override suspend fun answer(answer: RespondParams): Boolean {
        owner(answer.sessionId) ?: return false
        val pending = harness.log.pending(answer.sessionId).firstOrNull { it.id == answer.requestId } ?: return false
        val given = answer.answer
        return when {
            pending is PendingInteraction.Approval && given is InteractionAnswer.Approval -> harness.approvals.respond(pending.id, given.decision)
            pending is PendingInteraction.Approval && given is InteractionAnswer.Harness -> {
                val option = (given.answer as? JsonObject)?.get("optionId")?.jsonPrimitive?.content
                require(option == null || pending.forHarness().options.any { it.id == option })
                harness.approvals.respond(pending.id, approvalDecision(option))
            }
            pending is PendingInteraction.Questions && given is InteractionAnswer.Questions -> harness.questions.respond(pending.id, given.answers)
            else -> false
        }
    }

    override suspend fun send(params: SendParams): SendResult = send(params, null) {}

    suspend fun send(p: SendParams, requested: List<UiCatalog>?, onEvent: suspend (AgentEvent) -> Unit): SendResult {
        val catalogs = requested ?: harness.uiCatalogs.forRun()
        val agent = agent(p.agentId)
        require(p.text.isNotBlank() || p.attachments.isNotEmpty()) { "Add a message or attachment" }
        check(p.attachments.isEmpty() || ServerFeature.Attachments in capabilities().features) { "Attachments are not available on this Server" }
        require(p.attachments.size <= MAX_ATTACHMENTS && p.attachments.distinct().size == p.attachments.size) { "Attach up to 4 different files" }
        val attachments = withContext(Dispatchers.Default) { p.attachments.map(harness.attachments::get) }
        require(attachments.sumOf { it.size } <= MAX_ATTACHMENT_TOTAL_BYTES) { "Attachments must total 20 MB or less" }
        val sessionId = p.sessionId?.also { id ->
            check(harness.store.sessions(agent.id).any { it.id == id }) { "No session '$id' for agent '${agent.id}'" }
        } ?: harness.newSession(agent).id
        val effective = harness.capabilities(agent, sessionId, null, capabilities())
        effective.chat.requireReady()
        attachments.forEach { attachment -> effective.attachment(attachment.mimeType).requireReady() }
        val (activation, message) = (if (ServerFeature.Skills in capabilities().features) harness.slashActivation(agent, p.text) else null) ?: (null to p.text)
        val kind = RunKind.Chat(activation, attachments, p.voice, catalogs = catalogs, reports = toolReports(agent, sessionId, catalogs))
        val run = runs.start(agent, sessionId, ExecutionInput(message, action = p.action, kind = kind), onEvent = onEvent, onDone = { ran(agent, sessionId) })
        return SendResult(sessionId, run.id, message, activation)
    }

    private fun toolReports(agent: Agent, sessionId: String, catalogs: List<UiCatalog>): ToolReports = ToolReports(scope) { report ->
        try {
            val kind = RunKind.Chat(report = report, catalogs = catalogs, reports = toolReports(agent, sessionId, catalogs))
            runs.start(agent, sessionId, ExecutionInput("", kind = kind), queued = true, onEvent = { event ->
                if (event is AgentEvent.TextMessageEnd) reported(sessionId, event.text)
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.warn(e) { "Background report could not start in Session $sessionId" } }
    }

    override suspend fun stop(sessionId: String) { runs.stop(sessionId) }
}
