package kinuvo.harness.builtin

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentRunner
import kinuvo.harness.NativeHarness
import kinuvo.harness.NativeHarnessHost
import kinuvo.harness.NativeReflection
import kinuvo.harness.NativeRun
import kinuvo.harness.ModelSelection
import kinuvo.harness.builtin.knowledge.runReflection
import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.harness.ExecutionAcceptance
import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionSession
import kinuvo.harness.ExecutionState
import kinuvo.harness.RunKind
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.execution.AgentRegistration
import kinuvo.protocol.execution.ExecutionCapabilities
import kinuvo.protocol.execution.ExecutionConnection
import kinuvo.protocol.execution.HarnessConnection
import kinuvo.protocol.approval.InteractionAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/** Runs prompts with Kinuvo's own agent loop, choosing the Koog strategy by [RunKind]. [models] are the sources a host registered; [modelFactory] replaces them, e.g. in tests. */
class NativeExecutionFactory(
    private val host: NativeHarnessHost,
    private val models: () -> List<ModelSource> = { emptyList() },
    private val modelFactory: ((AgentConfig) -> AgentModel)? = null,
) : NativeHarness {
    private val tracers = ConcurrentMutableMap<Path, AgentTracing>()

    override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession =
        NativeExecutionSession(this, host, registration.id, state)

    override fun runner(run: NativeRun): AgentRunner =
        AgentRun(run.agent, run.store, model(run.model), run.tools, run.gate, run.warnings, run.context, run.contextTokens, tracing = tracing(run.traces), routine = run.routine, profile = run.profile)

    internal fun model(selection: ModelSelection): AgentModel = modelFactory?.invoke(selection.config)
        ?: (models().firstOrNull { it.id == selection.config.provider } ?: error("No model provider '${selection.config.provider}'")).model(selection.config, selection.info)

    internal fun resolve(reflection: NativeReflection): Pair<Agent, AgentModel> =
        try { reflection.agent to model(reflection.model) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { reflection.fallback?.let(::resolve) ?: throw e }

    internal fun tracing(traces: Path?): AgentTracing? = traces?.let { path -> tracers.block { it.getOrPut(path) { localTracing(path) } } }

    override fun close() {
        val open = tracers.block { it.values.toList().also { _ -> it.clear() } }
        open.forEach { it.close() }
    }
}

private class NativeExecutionSession(private val factory: NativeExecutionFactory, private val host: NativeHarnessHost, private val agentId: String, private val state: ExecutionState) : ExecutionSession {
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
                val (agent, model) = factory.resolve(reflection)
                runReflection(kind.turn, agent, model, sessionId, factory.tracing(reflection.traces))
            }
            is RunKind.Chat, is RunKind.Routine -> {
                val sink = state.boundSink()
                val runId = input.runId ?: Uuid.random().toString()
                val run = try { factory.runner(host.run(agentId, sessionId, input)) }
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
