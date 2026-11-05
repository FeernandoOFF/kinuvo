package kinuvo.protocol

import kotlin.test.*

class PushTest {
    @Test fun oldMessagesHaveNoActions() {
        val push = ProtocolJson.decodeFromString(PushMessage.serializer(), """{"title":"t","body":"b"}""")
        assertEquals(PushKind.Other, push.kind)
        assertNull(push.approvalId)
    }

    @Test fun actionsRoundTrip() {
        for (kind in PushKind.entries) {
            val push = PushMessage("t", "b", "agent", "session", true, "server", kind, "approval")
            assertEquals(push, ProtocolJson.decodeFromString(PushMessage.serializer(), ProtocolJson.encodeToString(PushMessage.serializer(), push)))
        }
    }
}
