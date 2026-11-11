package kinuvo.server

import kinuvo.server.plugin.AgentProgress
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceAgentProgressTest {
    @Test fun nativeIgnoresToolOutput() {
        val updates = NativeVoiceProgress()
        assertTrue(updates.update(AgentEvent.ToolCallResult("call", "raw output", false)).isEmpty())
    }

    @Test fun snapshotsDeduplicateRequestsAndTextWithoutInventingApprovalDecisions() {
        val updates = VoiceAgentProgress(ExecutionSnapshot("s"))
        val waiting = ExecutionSnapshot("s", ExecutionPhase.RequiresAction,
            messages = listOf(ExecutionMessage("m", "assistant", "Checking")),
            pending = listOf(PendingInteraction.HarnessApproval("r", "Agent action", null, emptyList())))
        assertEquals(2, updates.update(waiting).size)
        assertTrue(updates.update(waiting).isEmpty())
        assertEquals(listOf(AgentProgress.ApprovalResolved(null)), updates.update(waiting.copy(pending = emptyList())))
    }

    @Test fun theLatestMessageWaitsUntilSuperseded() {
        val updates = VoiceAgentProgress(ExecutionSnapshot("s"))
        val streaming = ExecutionSnapshot("s", ExecutionPhase.Running, messages = listOf(ExecutionMessage("m", "assistant", "Che")))
        assertTrue(updates.update(streaming).isEmpty())
        assertTrue(updates.update(streaming.copy(messages = listOf(ExecutionMessage("m", "assistant", "Checking the build")))).isEmpty())
        val next = streaming.copy(messages = listOf(ExecutionMessage("m", "assistant", "Checking the build"), ExecutionMessage("n", "assistant", "Done")))
        assertEquals(listOf<AgentProgress>(AgentProgress.Text("Checking the build")), updates.update(next))
        assertTrue(updates.update(next).isEmpty())
    }

    @Test fun nativeReportsMessagesOnlyOnceWorkFollows() {
        val updates = NativeVoiceProgress()
        assertTrue(updates.update(AgentEvent.TextMessageEnd("m", "Let me run the tests")).isEmpty())
        assertEquals(listOf<AgentProgress>(AgentProgress.Text("Let me run the tests")), updates.update(AgentEvent.ToolCallStart("t", "shell", "{}")))
        assertTrue(updates.update(AgentEvent.TextMessageEnd("r", "All green")).isEmpty(), "The reply is not progress")
        assertEquals(listOf<AgentProgress>(AgentProgress.ApprovalResolved(false)), updates.update(AgentEvent.InteractionResolved("call", false)))
    }
}
