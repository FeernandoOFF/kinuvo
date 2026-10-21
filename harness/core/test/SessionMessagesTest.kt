package kinuvo.harness.core

import ai.koog.prompt.message.MessagePart
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.Attachment
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.*

class SessionMessagesTest {
    private val messages = SessionMessages(scriptedModel { _, _ -> emptyFlow() })

    @Test
    fun identicalMessagesKeepTheirOwnIdentity() {
        val history = listOf(SessionEntry.AssistantMessage("same", "first"), SessionEntry.AssistantMessage("same", "second"))
        val prompt = messages.prompt("", history)
        assertEquals(history, messages.entries(prompt.messages))
    }

    @Test
    fun attachmentOnlyMessagesHaveNoEmptyTextPart() {
        val prompt = messages.prompt("", listOf(SessionEntry.UserMessage("", attachments = listOf(Attachment("f", "f.png", "image/png", 1)))))
        assertTrue(prompt.messages.single().parts.none { it is MessagePart.Text })
    }
}
