package kinuvo.app

import kinuvo.app.session.SessionData
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.*
import kotlin.test.*

class SubAgentStateTest {
    @Test fun outOfOrderBatchResultsUseChildIdentityAndKeepCallIdentity() {
        val agent = AgentId("agent")
        val first = SubAgentState(SessionId("first"), agent, ToolCallId("batch"), "one")
        val second = SubAgentState(SessionId("second"), agent, ToolCallId("batch"), "two")
        var live = LiveState().on(AgentEvent.SubAgentChanged(first)).on(AgentEvent.SubAgentChanged(second))
        live = live.on(AgentEvent.SubAgentChanged(second.copy(status = SubAgentStatus.Completed, result = "two result")))
            .on(AgentEvent.SubAgentChanged(first.copy(status = SubAgentStatus.Completed, result = "one result")))
        val data = SessionData(agent, live = live)
        assertEquals("one result", data.children[first.sessionId]?.result)
        assertEquals("two result", data.children[second.sessionId]?.result)
        assertEquals(ToolCallId("batch"), data.children[first.sessionId]?.callId)
        val restored = SessionData(agent, entries = data.children.values.map { SessionEntry.Notice(it.sessionId.value, "sub_agent", "", child = it) })
        assertEquals(data.children, restored.children)
    }
}
