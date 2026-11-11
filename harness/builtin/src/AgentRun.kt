package kinuvo.harness.builtin

import kinuvo.harness.Agent
import kinuvo.harness.AgentRunner
import kinuvo.harness.ModelProfile
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.prompt.Prompt
import kinuvo.harness.builtin.routines.routineStrategy
import kinuvo.harness.RoutineTurn
import kinuvo.harness.SessionStorage
import kinuvo.harness.builtin.tools.toDescriptor
import kinuvo.harness.ToolGate
import kinuvo.harness.ToolProvider
import kinuvo.harness.ToolTurn
import kinuvo.protocol.model.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.usage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Koog's default, for each Reflection stage. */
internal const val STAGE_ITERATIONS = 50

/** One turn of an Agent, with a fresh Koog graph and a durable Session. */
class AgentRun(
    private val agent: Agent,
    private val store: SessionStorage,
    private val model: AgentModel,
    private val tools: ToolProvider,
    private val gate: ToolGate,
    private val warnings: List<String> = emptyList(),
    private val context: String = "",
    private val contextTokens: Int? = null,
    private val requestKind: RequestKind = RequestKind.Chat,
    private val tracing: AgentTracing? = null,
    private val traceSessionId: SessionId? = null,
    private val routine: RoutineTurn? = null,
    private val profile: ModelProfile = ModelProfile.of(ModelTier.Frontier),
) : AgentRunner {
    override fun run(
        sessionId: SessionId,
        userText: String,
        activation: SessionEntry.Activation?,
        runId: RunId,
        attachments: List<Attachment>,
        voice: Boolean,
        action: SurfaceAction?,
        report: SessionEntry.ToolReport?,
    ): Flow<AgentEvent> = channelFlow {
        send(AgentEvent.RunStarted(runId))
        warnings.forEach { send(AgentEvent.Warning(it)) }
        val session = RunSession(agent, store, sessionId, runId, model, tools, requestKind, contextTokens, profile.history.latestTurnOnly, profile, { send(it) }, voice = voice)
        var stopped = "Cancelled by user"
        var cancelled = false
        val turn = ToolTurn(
            progress = { send(it) },
            stopped = { stopped = it },
            emit = { event ->
                if (event is AgentEvent.ToolCallStart) session.toolStarted(event.toolCallId)
                if (event !is AgentEvent.ToolCallResult || session.calls.none { it.id == event.toolCallId }) send(event)
            },
        )
        val calls = ToolCalls(tools, gate, turn)
        val result: AgentEvent = try {
            if (context.isNotBlank() && store.entries(agent.id, sessionId).activeHistory().filterIsInstance<SessionEntry.Context>().lastOrNull()?.content != context)
                session.append(SessionEntry.Context(context))
            activation?.let { session.append(it) }
            report?.let { session.append(it) }
            if (routine == null && (userText.isNotBlank() || attachments.isNotEmpty())) session.append(SessionEntry.UserMessage(userText, null, attachments, voice, action = action))
            val koog = AIAgent(
                id = agent.id.value,
                promptExecutor = model.executor,
                agentConfig = AIAgentConfig(Prompt.Empty, model.model, Int.MAX_VALUE),
                strategy = routine?.let { routineStrategy(session, calls, it, userText) } ?: chatStrategy(session, calls),
            ) {
                install(ToolLoopFeature) {
                    requests = session.requests
                    response = session.response
                    starting = session::startRequest
                    saveResponse = session::saveResponse
                }
                install(SessionFeature) { this.session = session }
                tracing?.install(this, agent.id, traceSessionId ?: sessionId, runId, requestKind)
            }
            try { withContext(TokenUsageCapture()) { koog.run(Unit, runId.value) } }
            finally { koog.close() }
            AgentEvent.RunFinished(runId)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            stopped = "Stopped before this call could run"
            val message = if (e is AIAgentMaxNumberOfIterationsReachedException || e is RequestLimitReached)
                "The work is unfinished; send another message to continue."
            else model.failure(e).message ?: e.toString()
            AgentEvent.RunError(runId, message)
        } finally {
            withContext(NonCancellable) {
                session.saveResponse()
                session.closePending(stopped, if (cancelled) "Not run: the turn was cancelled" else "Not run: the turn stopped") { event ->
                    if (cancelled) trySend(event) else send(event)
                }
            }
        }
        send(result)
    }.buffer(0)
}
