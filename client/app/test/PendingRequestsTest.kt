package kinuvo.app

import kinuvo.app.session.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.knowledge.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PendingRequestsTest {
    private val approval = PendingInteraction.Approval(InteractionId("a1"), ToolCallId("c1"), "shell", """{"command":"ls"}""", trustHint = "ls", reason = "Lists files")
    private val form = PendingInteraction.Questions(InteractionId("q1"), ToolCallId("c2"), "Deploy?", listOf(Question("env", "Environment")))
    private val takeover = PendingInteraction.Questions(InteractionId("q2"), ToolCallId("c3"), "Take over Browser: Sign in.", listOf(Question("note", "Note")))
    private val external = PendingInteraction.HarnessApproval(InteractionId("p1"), "Allow edit?", null, emptyList())

    private fun state(live: LiveState = LiveState(running = true), prompts: List<PendingInteraction> = emptyList(), vararg calls: SessionEntry.ToolCall) =
        ChatState(items = SessionData(AgentId("main"), entries = calls.toList(), live = live).items, pending = live.pending + prompts)

    @Test fun emptyWhenNothingWaits() {
        assertTrue(state().requests.isEmpty())
        assertTrue(state(calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c1"), "shell", "{}"))).requests.isEmpty())
    }

    @Test fun approvalCarriesWhatTheCardNeeds() {
        val request = assertIs<PendingRequest.Approval>(state(LiveState(running = true, pending = listOf(approval)), calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c1"), "shell", "{}"))).requests.single())
        assertEquals(InteractionId("a1"), request.id)
        assertEquals(approval, request.event)
        assertEquals("ls", request.event.trustHint)
    }

    @Test fun questionsAreAForm() {
        val request = assertIs<PendingRequest.Questions>(state(LiveState(running = true, pending = listOf(form)), calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c2"), "ask_user", "{}"))).requests.single())
        assertEquals(InteractionId("q1"), request.id)
        assertEquals(form, request.asked)
    }

    @Test fun takeoverIsNotQuestions() {
        val request = assertIs<PendingRequest.Takeover>(
            state(LiveState(running = true, pending = listOf(takeover)), calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c3"), "request_takeover", """{"reason":"Sign in"}"""))).requests.single(),
        )
        assertEquals(InteractionId("q2"), request.id)
        assertEquals("Sign in", request.reason)
        assertEquals(takeover, request.asked)
    }

    @Test fun externalPromptsWrapTheHarnessPrompt() {
        assertEquals(listOf(PendingRequest.External(external)), state(prompts = listOf(external)).requests)
    }

    @Test fun severalWaitInTranscriptOrderThenExternal() {
        val requests = state(
            LiveState(running = true, pending = listOf(approval, form)),
            listOf(external),
            SessionEntry.ToolCall(ToolCallId("c2"), "ask_user", "{}"),
            SessionEntry.ToolCall(ToolCallId("c1"), "shell", "{}"),
        ).requests
        assertEquals(listOf("q1", "a1", "p1").map(::InteractionId), requests.map { it.id })
    }

    @Test fun liveRequestsWithoutACallItemStillAppear() {
        val requests = state(LiveState(running = true, pending = listOf(approval, form))).requests
        assertEquals(listOf(PendingRequest.Approval(approval), PendingRequest.Questions(form)), requests)
    }

    @Test fun aCallWithAResultDoesNotWait() {
        val calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c1"), "shell", "{}"), SessionEntry.ToolResult(ToolCallId("c1"), "shell", "ok"))
        val items = SessionData(AgentId("main"), entries = calls.toList()).items
        assertTrue(ChatState(items = items).requests.isEmpty())
    }

    @Test fun takeoverReasonIsOnlyAStringArgument() {
        fun reason(args: String) = assertIs<PendingRequest.Takeover>(
            state(LiveState(running = true, pending = listOf(takeover)), calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c3"), "request_takeover", args))).requests.single(),
        ).reason
        assertEquals(null, reason("{}"))
        assertEquals(null, reason("not json"))
        assertEquals(null, reason("""{"reason":5}"""))
        assertEquals(null, reason("""{"reason":null}"""))
        assertEquals("Sign in", reason("""{"reason":"Sign in"}"""))
    }

    @Test fun approvalAndTakeoverKeepTranscriptOrder() {
        val requests = state(
            LiveState(running = true, pending = listOf(approval, takeover)),
            calls = arrayOf(SessionEntry.ToolCall(ToolCallId("c3"), "request_takeover", "{}"), SessionEntry.ToolCall(ToolCallId("c1"), "shell", "{}")),
        ).requests
        assertEquals(listOf("q2", "a1").map(::InteractionId), requests.map { it.id })
        assertIs<PendingRequest.Takeover>(requests.first())
    }
}
