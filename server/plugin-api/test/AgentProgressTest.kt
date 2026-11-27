package kinuvo.server.plugin

import kotlin.test.Test
import kotlin.test.assertTrue

class AgentProgressTest {
    @Test fun progressKeepsApprovalsOnScreenAndBoundsDetails() {
        val text = AgentProgress.ApprovalRequested("shell", "x".repeat(5000)).voiceText()
        assertTrue(text.contains("never by voice"))
        assertTrue(text.length < 600)
        assertTrue(AgentProgress.ApprovalResolved(false).voiceText().contains("denied"))
        assertTrue(AgentProgress.ApprovalResolved(null).voiceText().contains("do not assume"))
    }
}
