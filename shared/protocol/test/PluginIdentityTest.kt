package kinuvo.protocol

import kotlin.test.*

class PluginIdentityTest {
    @Test fun identitiesAreUnambiguousAndRoundTripWithTranscripts() {
        val first = ToolIdentity(PluginInstanceId("example.plugin", "a/b"), "c")
        val second = ToolIdentity(PluginInstanceId("example.plugin", "a"), "b/c")
        assertNotEquals(first.key, second.key)
        assertEquals("tool:example.plugin/a%2Fb/c", first.key)
        val entry = SessionEntry.ToolCall("call", "model_alias", "{}", first)
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        val event = AgentEvent.ApprovalRequested("approval", "call", "model_alias", "{}", identity = first)
        assertEquals(event, ProtocolJson.decodeFromString(AgentEvent.serializer(), ProtocolJson.encodeToString(AgentEvent.serializer(), event)))
    }
}
