package kinuvo.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SessionEntryTimeTest {
    @Test fun toolReportRoundTrips() {
        val entry: SessionEntry = SessionEntry.ToolReport("shell", "call", "done", 123L)
        assertEquals(entry, ProtocolJson.decodeFromString<SessionEntry>(ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        assertEquals(456L, entry.withWrittenAt(456L).writtenAt)
    }

    @Test
    fun optionalTimeRoundTripsAndLegacyEntriesDecode() {
        val legacy = """{"type":"assistant","text":"reply","messageId":"reply"}"""
        val entry = ProtocolJson.decodeFromString<SessionEntry>(legacy)
        assertEquals(null, entry.writtenAt)
        val stamped = entry.withWrittenAt(123L)
        val encoded = ProtocolJson.encodeToString(SessionEntry.serializer(), stamped)
        assertEquals(stamped, ProtocolJson.decodeFromString<SessionEntry>(encoded))
        val oldFields = ProtocolJson.parseToJsonElement(encoded).jsonObject.filterKeys { it != "writtenAt" }
        assertEquals(entry, ProtocolJson.decodeFromString<SessionEntry>(JsonObject(oldFields).toString()))
    }
}
