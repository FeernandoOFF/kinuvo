package kinuvo.protocol.surface

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SurfaceActionTest {
    @Test fun oldUserMessageDecodesWithoutAction() {
        val entry = ProtocolJson.decodeFromString(SessionEntry.UserMessage.serializer(), """{"text":"hello"}""")
        assertNull(entry.action)
        assertEquals("hello", entry.text)
    }

    @Test fun actionRoundTripsThroughSendAndSession() {
        val model = JsonObject(mapOf("name" to JsonPrimitive("Alex")))
        val action = SurfaceAction(ToolCallId("call"), "form", "save", "submit", model, model)
        val entry = SessionEntry.UserMessage("Save plan", action = action)
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        val params = SendParams(AgentId("agent"), SessionId("session"), entry.text, action = action)
        assertEquals(params, ProtocolJson.decodeFromString(SendParams.serializer(), ProtocolJson.encodeToString(SendParams.serializer(), params)))
    }
}
