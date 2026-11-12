package kinuvo.harness.builtin.knowledge

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.prompt.Prompt
import kinuvo.harness.Agent
import kinuvo.harness.builtin.AgentModel
import kinuvo.harness.builtin.AgentTracing
import kinuvo.harness.builtin.ModelRequests
import kinuvo.harness.builtin.STAGE_ITERATIONS
import kinuvo.harness.builtin.SessionMessages
import kinuvo.harness.builtin.StreamResponse
import kinuvo.harness.builtin.TokenUsageCapture
import kinuvo.harness.builtin.ToolCalls
import kinuvo.harness.builtin.ToolLoopFeature
import kinuvo.harness.builtin.tools.toDescriptor
import kinuvo.harness.ReflectionTurn
import kinuvo.harness.ToolProvider
import kinuvo.harness.ToolTurn
import kinuvo.protocol.usage.RequestKind
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.RunId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Runs each of [turn]'s stages as its own Koog run with the Reflection strategy; the Server owns what each stage may call. */
suspend fun runReflection(turn: ReflectionTurn, agent: Agent, model: AgentModel, sessionId: SessionId, tracing: AgentTracing?) {
    for (stage in turn.stages) {
        val runId = Uuid.random().toString()
        val run = ReflectionStageRun(turn, agent, model)
        val koog = AIAgent(
            id = agent.id.value,
            promptExecutor = model.executor,
            agentConfig = AIAgentConfig(Prompt.Empty, model.model, STAGE_ITERATIONS),
            strategy = reflectionStrategy(run, stage),
        ) {
            install(ToolLoopFeature) { requests = run.requests; response = run.response }
            tracing?.install(this, agent.id, sessionId, RunId(runId), RequestKind.Reflection)
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
