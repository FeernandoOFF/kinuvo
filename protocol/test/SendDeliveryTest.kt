package kinuvo.protocol

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.capability.SessionCapabilities
import kinuvo.protocol.session.*
import kotlin.test.*

class SendDeliveryTest {
    @Test fun olderMessagesDefaultToTurnAndOlderCapabilitiesDisableNewDelivery() {
        val send = ProtocolJson.decodeFromString<SendParams>("""{"agentId":"a","text":"hello"}""")
        assertEquals(SendDelivery.Turn, send.delivery)
        assertFalse(send.discovery)
        val caps = ProtocolJson.decodeFromString<SessionCapabilities>(ProtocolJson.encodeToString(SessionCapabilities.serializer(), SessionCapabilities(server = ServerCapabilities("test"))))
        assertFalse(caps.queue)
        assertFalse(caps.steer)
    }

    @Test fun queueSurvivesRunBoundariesAndRoundTrips() {
        val queue = listOf(QueuedMessage("q", "next"))
        val live = LiveState().on(AgentEvent.QueueChanged(queue))
            .on(AgentEvent.RunStarted(RunId("r"))).on(AgentEvent.RunFinished(RunId("r")))
        assertEquals(queue, ProtocolJson.decodeFromString<LiveState>(ProtocolJson.encodeToString(LiveState.serializer(), live)).queued)
        assertEquals(emptyList(), live.on(AgentEvent.QueueChanged(emptyList())).queued)
        val send = SendParams(AgentId("a"), text = "hello", delivery = SendDelivery.Steer, discovery = true)
        assertEquals(send, ProtocolJson.decodeFromString<SendParams>(ProtocolJson.encodeToString(SendParams.serializer(), send)))
    }
}
