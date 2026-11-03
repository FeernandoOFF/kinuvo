package kinuvo.app

import kinuvo.app.session.*
import kinuvo.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PendingRequestsTest {
    private val approval = AgentEvent.ApprovalRequested("a1", "c1", "shell", """{"command":"ls"}""", trustHint = "ls", reason = "Lists files")
    private val form = AgentEvent.QuestionsAsked("q1", "c2", "Deploy?", listOf(Question("env", "Environment")))
    private val takeover = AgentEvent.QuestionsAsked("q2", "c3", "Take over Browser: Sign in.", listOf(Question("note", "Note")))
    private val external = ExecutionPrompt.Approval("p1", "Allow edit?", null, emptyList())

    private fun state(live: LiveState = LiveState(running = true), prompts: List<ExecutionPrompt> = emptyList(), vararg calls: SessionEntry.ToolCall) =
        ChatState(items = SessionData("main", entries = calls.toList(), live = live).items, approval = live.approval, questions = live.questions, prompts = prompts)

    @Test fun emptyWhenNothingWaits() {
        assertTrue(state().requests.isEmpty())
        assertTrue(state(calls = arrayOf(SessionEntry.ToolCall("c1", "shell", "{}"))).requests.isEmpty())
    }

    @Test fun approvalCarriesWhatTheCardNeeds() {
        val request = assertIs<PendingRequest.Approval>(state(LiveState(running = true, approval = approval), calls = arrayOf(SessionEntry.ToolCall("c1", "shell", "{}"))).requests.single())
        assertEquals("a1", request.id)
        assertEquals(approval, request.event)
        assertEquals("ls", request.event.trustHint)
    }

    @Test fun questionsAreAForm() {
        val request = assertIs<PendingRequest.Questions>(state(LiveState(running = true, questions = form), calls = arrayOf(SessionEntry.ToolCall("c2", "ask_user", "{}"))).requests.single())
        assertEquals("q1", request.id)
        assertEquals(form, request.asked)
    }

    @Test fun takeoverIsNotQuestions() {
        val request = assertIs<PendingRequest.Takeover>(
            state(LiveState(running = true, questions = takeover), calls = arrayOf(SessionEntry.ToolCall("c3", "request_takeover", """{"reason":"Sign in"}"""))).requests.single(),
        )
        assertEquals("q2", request.id)
        assertEquals("Sign in", request.reason)
        assertEquals(takeover, request.asked)
    }

    @Test fun externalPromptsWrapTheHarnessPrompt() {
        assertEquals(listOf(PendingRequest.External(external)), state(prompts = listOf(external)).requests)
    }

    @Test fun severalWaitInTranscriptOrderThenExternal() {
        val requests = state(
            LiveState(running = true, approval = approval, questions = form),
            listOf(external),
            SessionEntry.ToolCall("c2", "ask_user", "{}"),
            SessionEntry.ToolCall("c1", "shell", "{}"),
        ).requests
        assertEquals(listOf("q1", "a1", "p1"), requests.map { it.id })
    }

    @Test fun liveRequestsWithoutACallItemStillAppear() {
        val requests = state(LiveState(running = true, approval = approval, questions = form)).requests
        assertEquals(listOf(PendingRequest.Approval(approval), PendingRequest.Questions(form)), requests)
    }

    @Test fun aCallWithAResultDoesNotWait() {
        val calls = arrayOf(SessionEntry.ToolCall("c1", "shell", "{}"), SessionEntry.ToolResult("c1", "shell", "ok"))
        val items = SessionData("main", entries = calls.toList()).items
        assertTrue(ChatState(items = items).requests.isEmpty())
    }

    @Test fun takeoverReasonIsOnlyAStringArgument() {
        fun reason(args: String) = assertIs<PendingRequest.Takeover>(
            state(LiveState(running = true, questions = takeover), calls = arrayOf(SessionEntry.ToolCall("c3", "request_takeover", args))).requests.single(),
        ).reason
        assertEquals(null, reason("{}"))
        assertEquals(null, reason("not json"))
        assertEquals(null, reason("""{"reason":5}"""))
        assertEquals(null, reason("""{"reason":null}"""))
        assertEquals("Sign in", reason("""{"reason":"Sign in"}"""))
    }

    @Test fun approvalAndTakeoverKeepTranscriptOrder() {
        val requests = state(
            LiveState(running = true, approval = approval, questions = takeover),
            calls = arrayOf(SessionEntry.ToolCall("c3", "request_takeover", "{}"), SessionEntry.ToolCall("c1", "shell", "{}")),
        ).requests
        assertEquals(listOf("q2", "a1"), requests.map { it.id })
        assertIs<PendingRequest.Takeover>(requests.first())
    }
}
