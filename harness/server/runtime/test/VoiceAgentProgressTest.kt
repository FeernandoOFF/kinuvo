package kinuvo.harness.server

import kinuvo.harness.core.voice.AgentProgress
import kinuvo.protocol.*
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceAgentProgressTest {
    @Test fun snapshotsDeduplicateRequestsAndTextWithoutInventingApprovalDecisions() {
        val updates = VoiceAgentProgress(ExecutionSnapshot("s"))
        val waiting = ExecutionSnapshot("s", ExecutionPhase.RequiresAction,
            messages = listOf(ExecutionMessage("m", "assistant", "Checking")),
            pending = listOf(ExecutionRequest("r", ExecutionRequestKind.Approval, JsonNull)))
        assertEquals(2, updates.update(waiting).size)
        assertTrue(updates.update(waiting).isEmpty())
        assertEquals(listOf(AgentProgress.ApprovalResolved(null)), updates.update(waiting.copy(pending = emptyList())))
    }

    @Test fun nativeApprovalIncludesDecision() {
        assertEquals(AgentProgress.ApprovalResolved(false), AgentEvent.ApprovalResolved("call", false).voiceProgress())
    }
}
