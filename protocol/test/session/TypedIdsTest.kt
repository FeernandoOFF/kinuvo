package kinuvo.protocol.session

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class TypedIdsTest {
    @Test fun idsEncodeAsPlainJsonStrings() {
        assertEquals("\"main\"", ProtocolJson.encodeToString(AgentId("main")))
        assertEquals("\"s1\"", ProtocolJson.encodeToString(SessionId("s1")))
        assertEquals("\"r1\"", ProtocolJson.encodeToString(RunId("r1")))
        assertEquals("\"c1\"", ProtocolJson.encodeToString(ToolCallId("c1")))
        assertEquals(SessionId("s1"), ProtocolJson.decodeFromString<SessionId>("\"s1\""))
    }

    @Test fun dtosKeepTheirWireShape() {
        val info = SessionInfo(SessionId("s1"), AgentId("main"), "Title")
        val json = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(info)).toString()
        assertEquals(true, json.contains("\"id\":\"s1\"") && json.contains("\"agentId\":\"main\""))
        assertEquals(info, ProtocolJson.decodeFromString<SessionInfo>("""{"id":"s1","agentId":"main","title":"Title"}"""))
        assertEquals("""{"type":"RUN_STARTED","runId":"r1"}""", ProtocolJson.encodeToString<AgentEvent>(AgentEvent.RunStarted(RunId("r1"))))
    }

    @Test fun idsWorkAsMapKeys() {
        val serializer = MapSerializer(SessionId.serializer(), Int.serializer())
        assertEquals("""{"s1":1}""", ProtocolJson.encodeToString(serializer, mapOf(SessionId("s1") to 1)))
    }
}
