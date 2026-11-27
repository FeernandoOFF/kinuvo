package kinuvo.server

import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.VoiceTurn
import kinuvo.server.plugin.VoiceMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceContextTest {
    @Test fun seedsContextWithRolesAndLatestSummary() {
        val context = voiceContext("Ada", listOf(
            SessionEntry.Compaction("old"),
            SessionEntry.UserMessage("hello"),
            SessionEntry.AssistantMessage("hi"),
            SessionEntry.Compaction("latest"),
            SessionEntry.UserMessage(" ")
        ))
        assertEquals(listOf(VoiceMessage(true, "hello"), VoiceMessage(false, "hi")), context.history)
        assertTrue(context.instructions.contains("Ada"))
        assertTrue(context.instructions.contains("Delegate"))
        assertTrue(context.instructions.contains("acknowledge the request aloud before delegating"))
        assertTrue(context.instructions.contains("short spoken updates"))
        assertTrue(context.instructions.contains("never by voice"))
        assertTrue(context.instructions.contains("when results come back"))
        assertTrue(context.instructions.contains("latest"))
        assertFalse(context.instructions.contains("old"))
    }

    @Test fun boundsContextByCountMessageSizeAndTotalSize() {
        val short = voiceContext("Ada", (0..50).map { SessionEntry.UserMessage("$it") })
        assertEquals(40, short.history.size)
        assertEquals("11", short.history.first().text)
        val large = voiceContext("Ada", (0..50).map { SessionEntry.AssistantMessage("x".repeat(3_000)) })
        assertEquals(12, large.history.size)
        assertTrue(large.history.all { !it.user && it.text.length == 2_000 })
        assertEquals(24_000, large.history.sumOf { it.text.length })
    }

    @Test fun aCallsRecordedTurnsSeedLaterCalls() {
        val turns = listOf(VoiceTurn(true, "hello"), VoiceTurn(false, "hi"))
        assertEquals(turns.map { VoiceMessage(it.user, it.text) }, voiceContext("Agent", listOf(SessionEntry.VoiceCall("c", turns, 0, 0))).history)
    }
}
