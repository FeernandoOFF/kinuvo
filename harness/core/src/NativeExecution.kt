package kinuvo.harness.core

import kinuvo.harness.core.knowledge.runReflection
import kinuvo.harness.execution.api.ExecutionAcceptance
import kinuvo.harness.execution.api.ExecutionBroker
import kinuvo.harness.execution.api.ExecutionFactory
import kinuvo.harness.execution.api.ExecutionInput
import kinuvo.harness.execution.api.ExecutionSession
import kinuvo.harness.execution.api.ExecutionState
import kinuvo.harness.execution.api.RunKind
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.AgentRegistration
import kinuvo.protocol.ExecutionCapabilities
import kinuvo.protocol.ExecutionConnection
import kinuvo.protocol.HarnessConnection
import kinuvo.protocol.InteractionAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** The built-in harness has no connection to configure; this stands in for the one [ExecutionFactory.open] takes. */
val BUILT_IN_HARNESS = HarnessConnection("kinuvo", "Kinuvo", ExecutionConnection.Acp(listOf("kinuvo")))

fun builtInRegistration(agent: Agent) = AgentRegistration(agent.id, agent.name, BUILT_IN_HARNESS.id)

/** What the built-in harness asks the Server that hosts it for when it runs a prompt: the Server composes tools, instructions and models. */
interface NativeHarnessHost {
    /** The loop for a chat turn or Routine run of [sessionId]. */
    suspend fun run(agentId: String, sessionId: String, input: ExecutionInput): AgentRun

    /** The Agent and model the Reflection of [sessionId] runs with. */
    suspend fun reflection(agentId: String, sessionId: String): ReflectionModel
}

class ReflectionModel(val agent: Agent, val model: AgentModel, val tracing: AgentTracing? = null)

/** Runs prompts with Kinuvo's own agent loop, choosing the Koog strategy by [RunKind]. */
class NativeExecutionFactory(private val host: NativeHarnessHost) : ExecutionFactory {
    override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession =
        NativeExecutionSession(host, registration.id, state)
}

private class NativeExecutionSession(private val host: NativeHarnessHost, private val agentId: String, private val state: ExecutionState) : ExecutionSession {
    override val capabilities = ExecutionCapabilities(cancel = true, questions = true, approvals = true)
    private var turn: Job? = null

    /** Returns when the turn has ended, however it ended; a stopped turn has published its [AgentEvent.RunFinished]. */
    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance = coroutineScope {
        val job = launch { run(input) }
        turn = job
        try { job.join() } finally { turn = null }
        ExecutionAcceptance()
    }

    override suspend fun cancel() { turn?.cancel() }

    override suspend fun answer(requestId: String, answer: InteractionAnswer) {
        error("The built-in harness answers its pending requests through its Server")
    }

    private suspend fun run(input: ExecutionInput) {
        val sessionId = state.sessionId
        when (val kind = input.kind) {
            is RunKind.Reflection -> {
                val reflection = host.reflection(agentId, sessionId)
                runReflection(kind.turn, reflection.agent, reflection.model, sessionId, reflection.tracing)
            }
            is RunKind.Chat, is RunKind.Routine -> {
                val sink = state.boundSink()
                val runId = input.runId ?: Uuid.random().toString()
                val run = try { host.run(agentId, sessionId, input) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    sink.publish(AgentEvent.RunStarted(runId))
                    sink.publish(AgentEvent.RunError(runId, e.message ?: e.toString()))
                    return
                }
                val chat = input.kind as? RunKind.Chat
                try {
                    run.run(sessionId, input.text, chat?.activation, runId, chat?.attachments.orEmpty(), chat?.voice ?: false, input.action, chat?.report).collect { sink.publish(it) }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { sink.publish(AgentEvent.RunFinished(runId)) }
                    throw e
                }
            }
        }
    }
}
