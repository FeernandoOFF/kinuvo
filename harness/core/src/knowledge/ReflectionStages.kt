package kinuvo.harness.core.knowledge

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.prompt.Prompt
import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.AgentTracing
import kinuvo.harness.core.ModelRequests
import kinuvo.harness.core.STAGE_ITERATIONS
import kinuvo.harness.core.SessionMessages
import kinuvo.harness.core.StreamResponse
import kinuvo.harness.core.TokenUsageCapture
import kinuvo.harness.core.ToolCalls
import kinuvo.harness.core.ToolLoopFeature
import kinuvo.harness.core.tools.toDescriptor
import kinuvo.harness.execution.api.ReflectionTurn
import kinuvo.harness.execution.api.ToolProvider
import kinuvo.harness.execution.api.ToolTurn
import kinuvo.protocol.RequestKind
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Runs each of [turn]'s stages as its own Koog run with the Reflection strategy; the Server owns what each stage may call. */
internal suspend fun runReflection(turn: ReflectionTurn, agent: Agent, model: AgentModel, sessionId: String, tracing: AgentTracing?) {
    for (stage in turn.stages) {
        val runId = Uuid.random().toString()
        val run = ReflectionStageRun(turn, agent, model)
        val koog = AIAgent(
            id = agent.id,
            promptExecutor = model.executor,
            agentConfig = AIAgentConfig(Prompt.Empty, model.model, STAGE_ITERATIONS),
            strategy = reflectionStrategy(run, stage),
        ) {
            install(ToolLoopFeature) { requests = run.requests; response = run.response }
            tracing?.install(this, agent.id, sessionId, runId, RequestKind.Reflection)
        }
        try {
            withContext(TokenUsageCapture()) { koog.run(Unit, runId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            turn.failed(if (e is AIAgentMaxNumberOfIterationsReachedException) "$stage stopped at the iteration limit" else model.failure(e).message ?: e.toString())
        } finally {
            koog.close()
        }
    }
}

internal class ReflectionStageRun(private val turn: ReflectionTurn, agent: Agent, model: AgentModel) {
    val messages = SessionMessages(model)
    val response = StreamResponse()
    val requests = ModelRequests(agent.config, { RequestKind.Reflection }) { turn.measured(it) }
    private var provider: ToolProvider? = null
    lateinit var calls: ToolCalls
        private set
    val advertised get() = provider?.advertised.orEmpty().map { it.toDescriptor() }

    fun callable(name: String) = provider?.tools.orEmpty().any { it.name == name }

    suspend fun prepare(stage: String): Prompt? {
        val step = turn.prepare(stage) ?: return null
        provider = step.tools
        calls = ToolCalls(step.tools, step.gate, ToolTurn())
        return messages.prompt("", listOf(SessionEntry.UserMessage(step.request)))
    }

    fun error(message: String) = turn.failed(message)
}
