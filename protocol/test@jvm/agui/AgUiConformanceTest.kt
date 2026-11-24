package kinuvo.protocol.agui

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.Question
import kinuvo.protocol.approval.QuestionKind
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.execution.ExternalApprovalOption
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.RunId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.ToolCallId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgUiConformanceTest {
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(requireNotNull(javaClass.getResourceAsStream("/agui/schema.json")).readBytes().decodeToString())

    private fun translate(vararg events: AgentEvent, parent: RunId? = null): List<AgUiOutput> {
        val translator = AgUiRunTranslator(SessionId("session"), parent)
        return events.flatMap(translator::on)
    }

    private fun List<AgUiOutput>.run() = filterIsInstance<AgUiOutput.Run>().map { it.event }

    private fun conform(outputs: List<AgUiOutput>): List<AgUiEvent> {
        val events = outputs.run()
        events.forEach { event ->
            val json = ProtocolJson.encodeToString(AgUiEvent.serializer(), event)
            val errors = schema.validate(json, InputFormat.JSON)
            assertTrue(errors.isEmpty(), "$json\n${errors.joinToString("\n")}")
        }
        assertLifecycle(events)
        return events
    }

    private fun assertLifecycle(events: List<AgUiEvent>) {
        var running = false
        val text = mutableSetOf<String>()
        val reasoningMessages = mutableSetOf<String>()
        val reasoning = mutableSetOf<String>()
        val tools = mutableSetOf<String>()
        events.forEachIndexed { index, event ->
            if (index == 0) assertIs<AgUiEvent.RunStarted>(event)
            when (event) {
                is AgUiEvent.RunStarted -> { assertTrue(!running, "RUN_STARTED inside a run"); running = true }
                is AgUiEvent.RunFinished, is AgUiEvent.RunError -> {
                    assertTrue(running, "terminal outside a run")
                    assertTrue(text.isEmpty() && reasoningMessages.isEmpty() && reasoning.isEmpty() && tools.isEmpty(), "open items at $event: $text $reasoning $tools")
                    running = false
                }
                else -> assertTrue(running, "$event outside a run")
            }
            when (event) {
                is AgUiEvent.TextMessageStart -> assertTrue(text.add(event.messageId))
                is AgUiEvent.TextMessageContent -> assertTrue(event.messageId in text)
                is AgUiEvent.TextMessageEnd -> assertTrue(text.remove(event.messageId))
                is AgUiEvent.ReasoningStart -> assertTrue(reasoning.add(event.messageId))
                is AgUiEvent.ReasoningMessageStart -> assertTrue(reasoningMessages.add(event.messageId))
                is AgUiEvent.ReasoningMessageContent -> assertTrue(event.messageId in reasoningMessages)
                is AgUiEvent.ReasoningMessageEnd -> assertTrue(reasoningMessages.remove(event.messageId))
                is AgUiEvent.ReasoningEnd -> assertTrue(reasoning.remove(event.messageId))
                is AgUiEvent.ToolCallStart -> assertTrue(tools.add(event.toolCallId))
                is AgUiEvent.ToolCallArgs -> assertTrue(event.toolCallId in tools)
                is AgUiEvent.ToolCallEnd -> assertTrue(tools.remove(event.toolCallId))
                else -> Unit
            }
        }
        assertTrue(!running, "run left open")
    }

    private val run = RunId("run-1")
    private val call = ToolCallId("call-1")
    private fun approval(id: String = "approval-1") = PendingInteraction.Approval(InteractionId(id), call, "shell", "{\"cmd\":\"ls\"}", trustHint = "ls", reason = "Runs a command")

    @Test fun plainReply() {
        val events = conform(translate(
            AgentEvent.RunStarted(run),
            AgentEvent.TextMessageContent("m1", "Hel"), AgentEvent.TextMessageContent("m1", "lo"), AgentEvent.TextMessageEnd("m1", "Hello"),
            AgentEvent.RunFinished(run),
            parent = RunId("parent"),
        ))
        assertEquals("parent", assertIs<AgUiEvent.RunStarted>(events.first()).parentRunId)
        assertEquals(RunOutcome.Success(), assertIs<AgUiEvent.RunFinished>(events.last()).outcome)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<AgUiEvent.TextMessageContent>().map { it.delta })
    }

    @Test fun finalTextWithoutDeltasStillStreams() {
        val events = conform(translate(AgentEvent.RunStarted(run), AgentEvent.TextMessageEnd("m1", "Hi"), AgentEvent.RunFinished(run)))
        assertEquals("Hi", events.filterIsInstance<AgUiEvent.TextMessageContent>().single().delta)
    }

    @Test fun reasoningThenToolCallThenResult() {
        val events = conform(translate(
            AgentEvent.RunStarted(run),
            AgentEvent.ThinkingContent("r1", "think"), AgentEvent.ThinkingContent("r1", "ing"),
            AgentEvent.ToolCallStart(call, "shell", "{\"cmd\":\"ls\"}"),
            AgentEvent.ToolCallResult(call, "a.txt", false),
            AgentEvent.TextMessageContent("m1", "done"),
            AgentEvent.RunFinished(run),
        ))
        val types = events.map { it::class.simpleName }
        assertTrue(types.indexOf("ReasoningEnd") < types.indexOf("ToolCallStart"))
        assertEquals("a.txt", events.filterIsInstance<AgUiEvent.ToolCallResult>().single().content)
    }

    @Test fun repeatedToolResultIsActivityProgress() {
        val events = conform(translate(
            AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "exec", "{}"),
            AgentEvent.ToolCallResult(call, "partial", false), AgentEvent.ToolCallResult(call, "final", false),
            AgentEvent.RunFinished(run),
        ))
        assertEquals(1, events.filterIsInstance<AgUiEvent.ToolCallResult>().size)
        assertEquals("kinuvo:tool-progress", events.filterIsInstance<AgUiEvent.ActivitySnapshot>().single().activityType)
    }

    @Test fun approvalInterruptsTheRun() {
        val outputs = translate(
            AgentEvent.RunStarted(run), AgentEvent.TextMessageContent("m1", "Let me check"),
            AgentEvent.ToolCallStart(call, "shell", "{\"cmd\":\"ls\"}"),
            AgentEvent.InteractionRequested(approval()), AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction),
        )
        val events = conform(outputs)
        val outcome = assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(events.last()).outcome)
        val interrupt = outcome.interrupts.single()
        assertEquals("tool_call", interrupt.reason)
        assertEquals("call-1", interrupt.toolCallId)
        assertEquals(1, outputs.count { it is AgUiOutput.Item && it.event is AgentEvent.InteractionRequested })
    }

    @Test fun questionsInterrupt() {
        val questions = PendingInteraction.Questions(
            InteractionId("q"), call, "Plan trip", listOf(Question("where", "Where?", required = true), Question("how", "How?", QuestionKind.Single, listOf("car", "train"))),
        )
        val events = conform(translate(
            AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "ask_user", "{}"),
            AgentEvent.InteractionRequested(questions), AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction),
        ))
        val interrupt = assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(events.last()).outcome).interrupts.single()
        assertEquals("input_required", interrupt.reason)
        assertTrue(interrupt.responseSchema != null)
    }

    @Test fun kinuvoInteractionsKeepTheirReasons() {
        val pending = listOf(
            PendingInteraction.HarnessApproval(InteractionId("h"), "Allow?", null, listOf(ExternalApprovalOption("o", "Allow", "allow_once")), site = "example.com"),
            PendingInteraction.OpenLink(InteractionId("l"), "Sign in", "https://example.com/x", "example.com"),
            PendingInteraction.Unsupported(InteractionId("u"), "Nope"),
            PendingInteraction.Connect(InteractionId("c"), ToolCallId("tc"), AgentId("a"), "github", site = "github.com"),
        )
        val reasons = pending.map { interaction ->
            val events = conform(translate(AgentEvent.RunStarted(run), AgentEvent.InteractionRequested(interaction), AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction)))
            assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(events.last()).outcome).interrupts.single().reason
        }
        assertEquals(listOf("kinuvo:harness_approval", "kinuvo:open_link", "kinuvo:unsupported", "kinuvo:connect"), reasons)
    }

    @Test fun runWaitsForSiblingToolCallsBeforeItInterrupts() {
        val other = ToolCallId("call-2")
        val translator = AgUiRunTranslator(SessionId("session"))
        val before = listOf(
            AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "shell", "{}"), AgentEvent.ToolCallStart(other, "read", "{}"), AgentEvent.InteractionRequested(approval()),
        ).flatMap(translator::on)
        assertTrue(before.run().none { it is AgUiEvent.RunFinished })
        val after = translator.on(AgentEvent.ToolCallResult(other, "ok", false))
        val events = conform(before + after)
        val interrupt = assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(events.last()).outcome).interrupts.single()
        assertEquals("call-1", interrupt.toolCallId)
    }

    @Test fun answersOpenANewRunBeforeTheReleasedOutput() {
        val translator = AgUiRunTranslator(SessionId("session"))
        val events = conform(listOf(
            AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "shell", "{}"), AgentEvent.InteractionRequested(approval()),
            AgentEvent.InteractionResolved(InteractionId("approval-1"), true), AgentEvent.ToolCallResult(call, "a.txt", false),
            AgentEvent.TextMessageEnd("m2", "Done"), AgentEvent.RunFinished(run),
        ).flatMap(translator::on))
        assertEquals(listOf("run-1", "run-1~1"), events.filterIsInstance<AgUiEvent.RunStarted>().map { it.runId })
        val resumed = events.indexOfFirst { it is AgUiEvent.RunStarted && it.runId == "run-1~1" }
        assertTrue(events.indexOfFirst { it is AgUiEvent.ToolCallResult } > resumed)
        assertEquals(RunOutcome.Success(), assertIs<AgUiEvent.RunFinished>(events.last()).outcome)
    }

    @Test fun outputWhileAnswersAreOpenWaitsForTheNextRun() {
        val translator = AgUiRunTranslator(SessionId("session"))
        val parked = listOf(
            AgentEvent.RunStarted(run), AgentEvent.InteractionRequested(PendingInteraction.HarnessApproval(InteractionId("h"), "Allow?", null, emptyList())),
        ).flatMap(translator::on)
        assertTrue(translator.on(AgentEvent.TextMessageContent("late", "still working")).isEmpty())
        val released = listOf(AgentEvent.InteractionResolved(InteractionId("h")), AgentEvent.TextMessageEnd("m2", "Done"), AgentEvent.RunFinished(run)).flatMap(translator::on)
        val events = conform(parked + released)
        val resumed = events.dropWhile { it !is AgUiEvent.RunFinished }.drop(1)
        assertIs<AgUiEvent.RunStarted>(resumed.first())
        assertEquals("still working", resumed.filterIsInstance<AgUiEvent.TextMessageContent>().first().delta)
    }

    @Test fun stopOnAParkedRunReportsItemsAndNoNewRun() {
        val translator = AgUiRunTranslator(SessionId("session"))
        val parked = listOf(AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "shell", "{}"), AgentEvent.InteractionRequested(approval())).flatMap(translator::on)
        conform(parked)
        translator.stopping()
        val stopped = listOf(
            AgentEvent.InteractionResolved(InteractionId("approval-1")), AgentEvent.ToolCallResult(call, "Denied (stopped)", true),
            AgentEvent.RunFinished(run, cancelled = true), AgentEvent.Idle,
        ).flatMap(translator::on)
        assertTrue(stopped.none { it is AgUiOutput.Run })
        assertEquals(AgentEvent.Idle, (stopped.last() as AgUiOutput.Item).event)
        val next = translator.on(AgentEvent.RunStarted(RunId("run-2")))
        assertIs<AgUiEvent.RunStarted>((next.single() as AgUiOutput.Run).event)
    }

    @Test fun idleEndsAnActiveRunAsCancelled() {
        val events = conform(translate(AgentEvent.RunStarted(run), AgentEvent.TextMessageContent("m", "x"), AgentEvent.Idle))
        assertEquals(RunOutcome.Cancelled, assertIs<AgUiEvent.RunFinished>(events.last()).outcome)
    }

    @Test fun childInterruptsStayOutOfTheParentRun() {
        val outputs = translate(
            AgentEvent.RunStarted(run), AgentEvent.InteractionRequested(approval("child"), SessionId("child")),
            AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction), AgentEvent.RunFinished(run),
        )
        val events = conform(outputs)
        assertEquals(RunOutcome.Success(), assertIs<AgUiEvent.RunFinished>(events.last()).outcome)
    }

    @Test fun stopCancelsTheRunAndClosesOpenItems() {
        val events = conform(translate(
            AgentEvent.RunStarted(run), AgentEvent.ThinkingContent("r", "x"), AgentEvent.TextMessageContent("m", "partial"), AgentEvent.RunFinished(run, cancelled = true),
        ))
        assertEquals(RunOutcome.Cancelled, assertIs<AgUiEvent.RunFinished>(events.last()).outcome)
    }

    @Test fun lostIsRunError() {
        val events = conform(translate(AgentEvent.RunStarted(run), AgentEvent.TextMessageContent("m", "x"), AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Lost)))
        assertEquals("kinuvo:lost", assertIs<AgUiEvent.RunError>(events.last()).code)
    }

    @Test fun runErrorClosesOpenItems() {
        val events = conform(translate(AgentEvent.RunStarted(run), AgentEvent.ThinkingContent("r", "x"), AgentEvent.RunError(run, "boom")))
        assertEquals("boom", assertIs<AgUiEvent.RunError>(events.last()).message)
    }

    @Test fun toolResultInResumedRunForCallOpenedBeforeTheInterrupt() {
        val translator = AgUiRunTranslator(SessionId("session"))
        val first = listOf(
            AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "shell", "{}"), AgentEvent.InteractionRequested(approval()),
            AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction),
        ).flatMap(translator::on)
        val second = listOf(
            AgentEvent.InteractionResolved(InteractionId("approval-1"), true), AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running), AgentEvent.RunStarted(RunId("run-2")),
            AgentEvent.ToolCallResult(call, "a.txt", false), AgentEvent.TextMessageEnd("m2", "Done"), AgentEvent.RunFinished(RunId("run-2")),
        ).flatMap(translator::on)
        val events = conform(first + second)
        assertEquals(listOf("run-1", "run-2"), events.filterIsInstance<AgUiEvent.RunStarted>().map { it.runId })
        assertEquals(1, events.filterIsInstance<AgUiEvent.ToolCallStart>().size)
        assertEquals("a.txt", events.filterIsInstance<AgUiEvent.ToolCallResult>().single().content)
    }

    @Test fun eventsOutsideARunAreKinuvoItems() {
        val outputs = translate(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle), AgentEvent.Warning("w"), AgentEvent.QueueChanged(emptyList()))
        assertTrue(outputs.all { it is AgUiOutput.Item })
        assertEquals(3, outputs.size)
    }

    @Test fun schemaRejectsInvalidEvents() {
        assertTrue(schema.validate("""{"type":"RUN_ERROR"}""", InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate("""{"type":"TEXT_MESSAGE_END","messageId":"m","extra":1}""", InputFormat.JSON).isNotEmpty())
    }

    @Test fun noRunEventAfterTerminal() {
        val translator = AgUiRunTranslator(SessionId("session"))
        listOf(AgentEvent.RunStarted(run), AgentEvent.RunFinished(run)).forEach(translator::on)
        assertTrue(translator.on(AgentEvent.TextMessageContent("m", "late")).none { it is AgUiOutput.Run })
    }
}
