package kinuvo.harness.core

import kinuvo.protocol.SessionEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTextTest {
    @Test
    fun voiceTurnsAskForShortSpokenReplies() {
        val text = SessionEntry.UserMessage("whats on my calender", voice = true).modelText
        assertTrue(text.startsWith("[Voice chat:") && text.endsWith("\n\nwhats on my calender"))
        assertEquals("hi", SessionEntry.UserMessage("hi").modelText)
    }
}
