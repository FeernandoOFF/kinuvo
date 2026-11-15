package kinuvo.protocol.push

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.session.SessionId
import kotlin.test.*

class PushTest {
    @Test fun oldMessagesHaveNoActions() {
        val push = ProtocolJson.decodeFromString(PushMessage.serializer(), """{"title":"t","body":"b"}""")
        assertEquals(PushKind.Other, push.kind)
        assertNull(push.approvalId)
    }

    @Test fun actionsRoundTrip() {
        for (kind in PushKind.entries) {
            val push = PushMessage("t", "b", AgentId("agent"), SessionId("session"), true, "server", kind, InteractionId("approval"))
            assertEquals(push, ProtocolJson.decodeFromString(PushMessage.serializer(), ProtocolJson.encodeToString(PushMessage.serializer(), push)))
        }
    }
}
