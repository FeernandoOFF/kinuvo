package kinuvo.protocol

import kotlin.test.*

class RoomProtocolTest {
    @Test fun legacySessionsDefaultToNoPrivateExecutionsAndPreservePublicHistory() {
        val legacy = """{"id":"session","roomId":"room","messages":[{"id":"reply","sender":"deleted","senderName":"Deleted Agent","text":"Saved public answer"}]}"""
        val session = ProtocolJson.decodeFromString(RoomSession.serializer(), legacy)
        assertEquals(emptyMap(), session.memberExecutions)
        assertEquals(emptyMap(), session.memberSettings)
        assertEquals("Saved public answer", session.messages.single().text)
        val current = session.copy(memberExecutions = mapOf("agent" to RoomExecution("private", cursor = 1, interrupted = true)))
        assertEquals(current, ProtocolJson.decodeFromString(RoomSession.serializer(), ProtocolJson.encodeToString(RoomSession.serializer(), current)))
    }
}
