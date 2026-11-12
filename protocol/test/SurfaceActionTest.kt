package kinuvo.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class SurfaceActionTest {
    @Test fun oldUserMessageDecodesWithoutAction() {
        val entry = ProtocolJson.decodeFromString(SessionEntry.UserMessage.serializer(), """{"text":"hello"}""")
        assertNull(entry.action)
        assertEquals("hello", entry.text)
    }

    @Test fun actionRoundTripsThroughSendAndSession() {
        val model = JsonObject(mapOf("name" to JsonPrimitive("Alex")))
        val action = SurfaceAction("call", "form", "save", "submit", model, model)
        val entry = SessionEntry.UserMessage("Save plan", action = action)
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        val params = SendParams("agent", "session", entry.text, action = action)
        assertEquals(params, ProtocolJson.decodeFromString(SendParams.serializer(), ProtocolJson.encodeToString(SendParams.serializer(), params)))
    }
}
