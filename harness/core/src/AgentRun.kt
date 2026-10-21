package kinuvo.harness.core

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.indexedTools
import kinuvo.harness.core.tools.ToolExecutor
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** One turn of an Agent, with a fresh Koog graph and a durable Session. */
class AgentRun(
    private val agent: Agent,
    private val store: Store,
    private val model: AgentModel,
    private val tools: List<Tool>,
    private val warnings: List<String> = emptyList(),
    private val context: String = "",
    private val approvals: Approvals = Approvals.none(),
    private val questions: Broker<Map<String, List<String>>?>? = null,
    private val maxAgentIterations: Int = 120,
    advertisedTools: List<ToolDescriptor> = tools.map { it.descriptor },
    private val contextTokens: Int? = null,
    private val requestKind: RequestKind = RequestKind.Chat,
    private val onRequest: (String, List<SessionEntry>, List<ToolDescriptor>) -> Unit = { _, _, _ -> },
    private val attach: ((String, ByteArray) -> Attachment)? = null,
) {
    init { tools.indexedTools() }

    private val advertisedTools = advertisedTools.sortedBy { it.name }

    fun run(
        sessionId: String,
        userText: String,
        activation: SessionEntry.Activation? = null,
        runId: String = Uuid.random().toString(),
        routine: RoutineRef? = null,
        attachments: List<Attachment> = emptyList(),
        voice: Boolean = false,
    ): Flow<AgentEvent> = channelFlow {
        send(AgentEvent.RunStarted(runId))
        warnings.forEach { send(AgentEvent.Warning(it)) }
        val session = RunSession(agent, store, sessionId, runId, model, advertisedTools, tools, requestKind, contextTokens, { send(it) }, onRequest)
        var stopped = "Cancelled by user"
        val executor = ToolExecutor(tools, advertisedTools, approvals, questions, attach, emit = { event ->
            if (event !is AgentEvent.ToolCallResult || session.calls.none { it.id == event.toolCallId }) send(event)
        })
        try {
            if (context.isNotBlank() && store.entries(agent, sessionId).activeHistory().filterIsInstance<SessionEntry.Context>().lastOrNull()?.content != context)
                session.append(SessionEntry.Context(context))
            activation?.let { session.append(it) }
            session.append(SessionEntry.UserMessage(userText, routine, attachments, voice))
            val koog = AIAgent(
                promptExecutor = model.executor,
                agentConfig = AIAgentConfig(Prompt.Empty, model.model, maxAgentIterations),
                strategy = chatStrategy(session, executor) { stopped = it },
            ) { install(SessionFeature) { this.session = session } }
            try { withContext(TokenUsageCapture()) { koog.run(Unit, runId) } }
            finally { koog.close() }
            send(AgentEvent.RunFinished(runId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stopped = "Stopped before this call could run"
            val message = if (generateSequence<Throwable>(e) { it.cause }.any { it is AIAgentMaxNumberOfIterationsReachedException })
                "Stopped after $maxAgentIterations agent iterations. The work is unfinished; send another message to continue."
            else model.failure(e).message ?: e.toString()
            send(AgentEvent.RunError(runId, message))
        } finally {
            withContext(NonCancellable) {
                session.saveResponse()
                session.closePending(stopped)
            }
        }
    }.buffer(0)
}
