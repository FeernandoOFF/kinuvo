package kinuvo.protocol.mcp

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.session.SessionEntry
import kotlin.test.*
import kotlinx.serialization.json.*

class McpAppProtocolTest {
    @Test fun appResultRoundTripsAndOldEntriesStillDecode() {
        val ref = McpAppRef("weather", "ui://weather/app", "hash")
        val result = SessionEntry.ToolResult("call", "weather", "sunny", app = ref,
            appResult = buildJsonObject { put("structuredContent", buildJsonObject { put("temperature", 12) }) })
        assertEquals(result, ProtocolJson.decodeFromString<SessionEntry>(ProtocolJson.encodeToString<SessionEntry>(result)))
        val old = SessionEntry.ToolResult("call", "weather", "sunny")
        assertNull((ProtocolJson.decodeFromString<SessionEntry>(ProtocolJson.encodeToString<SessionEntry>(old)) as SessionEntry.ToolResult).app)
        val request = McpAppToolRequest(McpAppRequest("agent", "session", "call"), "refresh")
        assertEquals(request, ProtocolJson.decodeFromString<McpAppToolRequest>(ProtocolJson.encodeToString(request)))
    }
}
