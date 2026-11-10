package kinuvo.harness.server.testing

import kinuvo.harness.execution.api.ExecutionAcceptance
import kinuvo.harness.execution.api.ExecutionBroker
import kinuvo.harness.execution.api.ExecutionFactory
import kinuvo.harness.execution.api.ExecutionInput
import kinuvo.harness.execution.api.ExecutionSession
import kinuvo.harness.execution.api.ExecutionState
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.AgentRegistration
import kinuvo.protocol.ExecutionCapabilities
import kinuvo.protocol.ExecutionConnection
import kinuvo.protocol.ExecutionPhase
import kinuvo.protocol.ExecutionPrompt
import kinuvo.protocol.ExecutionProbe
import kinuvo.protocol.ExecutionReadiness
import kinuvo.protocol.ExecutionRequest
import kinuvo.protocol.ExecutionRequestKind
import kinuvo.protocol.ExternalApprovalOption
import kinuvo.protocol.HarnessConnection
import kinuvo.protocol.Question
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * An installed external harness, as the Server's real execution registry sees it: [connection] is online once probed and
 * every Session opened on it is a [FakeExternalSession] that answers each prompt by running [turn]. [capabilities] are
 * what its Sessions report, e.g. the file types attachments may have.
 */
class FakeHarness(
    val id: String = "fake",
    val name: String = "Fake harness",
    val capabilities: ExecutionCapabilities = ExecutionCapabilities(cancel = true, questions = true, approvals = true),
    private val turn: suspend FakeExternalSession.(String) -> Unit = { reply("Fake harness replied") },
) : ExecutionFactory {
    val connection = HarnessConnection(id, name, ExecutionConnection.Acp(listOf("fake-harness")))
    val sessions = CopyOnWriteArrayList<FakeExternalSession>()
    val probes = AtomicInteger()

    override suspend fun probe(config: HarnessConnection): ExecutionProbe {
        probes.incrementAndGet()
        return ExecutionProbe(ExecutionReadiness(true, true), title = name, version = "1.0")
    }

    override suspend fun open(
        config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope,
    ): ExecutionSession = FakeExternalSession(capabilities, state, broker, scope, turn).also {
        state.reference("fake-${sessions.size + 1}")
        sessions += it
    }
}

/** Routes each connection to its [FakeHarness]. */
internal class FakeHarnesses(harnesses: List<FakeHarness>) : ExecutionFactory {
    private val byId = harnesses.associateBy { it.id }

    override suspend fun probe(config: HarnessConnection) = harness(config).probe(config)

    override suspend fun open(
        config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope,
    ) = harness(config).open(config, registration, state, broker, scope)

    private fun harness(config: HarnessConnection) = requireNotNull(byId[config.id]) { "No fake harness ${config.id}" }
}

/**
 * One Session on a [FakeHarness]. Its turn writes through the same ordered sink an ACP adapter does: [say] commits an
 * assistant message, [ask] leaves a request pending until a client answers it, and the turn ends when the script returns.
 */
class FakeExternalSession internal constructor(
    override val capabilities: ExecutionCapabilities,
    private val state: ExecutionState,
    private val broker: ExecutionBroker,
    private val scope: CoroutineScope,
    private val turn: suspend FakeExternalSession.(String) -> Unit,
) : ExecutionSession {
    private val sink = state.boundSink()
    private val turns = AtomicInteger()
    private val messages = AtomicInteger()
    private val requests = AtomicInteger()
    private var job: Job? = null

    val prompts = CopyOnWriteArrayList<ExecutionInput>()

    private val runId get() = "${state.generation}:${turns.get()}"

    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
        prompts += input
        val number = turns.incrementAndGet()
        job = scope.launch {
            sink.publish(AgentEvent.RunStarted(runId))
            turn(input.text)
            finish("end_turn")
        }
        return ExecutionAcceptance("accepted-$number")
    }

    override suspend fun cancel() {
        job?.cancelAndJoin()
        if (state.snapshot().phase != ExecutionPhase.Idle) finish("cancelled")
    }

    override suspend fun answer(requestId: String, answer: JsonElement) {}

    /** Commits an assistant message to the Session's transcript. */
    suspend fun say(text: String) {
        val entry = SessionEntry.AssistantMessage(text, "${state.generation}:${turns.get()}:${messages.incrementAndGet()}")
        sink.publish(AgentEvent.ExecutionEntryChanged(entry))
        sink.commit(listOf(entry))
    }

    /** Same as [say]; reads as the turn's last word. */
    suspend fun reply(text: String) = say(text)

    /** Waits for a client's answer to [request], which is shown pending meanwhile. */
    suspend fun ask(request: ExecutionRequest): JsonElement {
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction))
        val answer = broker.ask(request)
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
        return answer
    }

    /** Asks a form of [questions], like an `ask_user` call. */
    suspend fun askQuestions(title: String, vararg questions: Question): JsonElement {
        val id = "request-${requests.incrementAndGet()}"
        return ask(ExecutionRequest(id, ExecutionRequestKind.Question, JsonObject(emptyMap()),
            prompt = ExecutionPrompt.Questions(id, AgentEvent.QuestionsAsked(id, id, title, questions.toList()))))
    }

    /** Asks the user to pick one of [options] for [title], like a permission request. */
    suspend fun askApproval(title: String, description: String? = null, options: List<ExternalApprovalOption> = listOf(ExternalApprovalOption("allow", "Allow", "allow_once"))): JsonElement {
        val id = "request-${requests.incrementAndGet()}"
        return ask(ExecutionRequest(id, ExecutionRequestKind.Approval, JsonObject(emptyMap()), options,
            ExecutionPrompt.Approval(id, title, description, options)))
    }

    private suspend fun finish(reason: String) {
        sink.publish(AgentEvent.RunFinished(runId))
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
        state.update(ExecutionPhase.Idle, reason)
    }
}
