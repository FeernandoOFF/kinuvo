package kinuvo.protocol.plugin

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.SessionEntry
import kotlin.test.*

class PluginIdentityTest {
    @Test fun identitiesAreUnambiguousAndRoundTripWithTranscripts() {
        val first = ToolIdentity(PluginInstanceId("example.plugin", "a/b"), "c")
        val second = ToolIdentity(PluginInstanceId("example.plugin", "a"), "b/c")
        assertNotEquals(first.key, second.key)
        assertEquals("tool:example.plugin/a%2Fb/c", first.key)
        val entry = SessionEntry.ToolCall("call", "model_alias", "{}", first)
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        val event = AgentEvent.InteractionRequested(PendingInteraction.Approval("approval", "call", "model_alias", "{}", identity = first))
        assertEquals(event, ProtocolJson.decodeFromString(AgentEvent.serializer(), ProtocolJson.encodeToString(AgentEvent.serializer(), event)))
    }
}
