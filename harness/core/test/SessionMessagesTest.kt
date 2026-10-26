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
    fun providerSwitchDropsForeignReasoningButKeepsLegacyOpenAI() {
        val history = listOf(
            SessionEntry.Reasoning("legacy", encrypted = "openai-ciphertext"),
            SessionEntry.Reasoning("thought", encrypted = "signature", format = "anthropic"),
            SessionEntry.AssistantMessage("answer"),
        )
        val openai = messages.prompt("", history).messages.single().parts
        assertEquals("openai-ciphertext", openai.filterIsInstance<MessagePart.Reasoning>().single().encrypted)
        val anthropic = SessionMessages(scriptedModel { _, _ -> emptyFlow() }.copy(reasoningFormat = "anthropic", reasoningInContent = true))
        val native = anthropic.prompt("", history).messages.single().parts
        assertEquals("signature", native.filterIsInstance<MessagePart.Reasoning>().single().encrypted)
        assertEquals("answer", native.filterIsInstance<MessagePart.Text>().single().text)
    }

    @Test
    fun deepSeekReplaysUnsignedReasoningWithToolCall() {
        val converter = SessionMessages(scriptedModel { _, _ -> emptyFlow() }.copy(reasoningFormat = "deepseek", reasoningInContent = true))
        val history = listOf(
            SessionEntry.Reasoning("need a tool", format = "deepseek"),
            SessionEntry.ToolCall("call", "lookup", "{}"),
            SessionEntry.ToolResult("call", "lookup", "found"),
        )
        val prompt = converter.prompt("", history)
        val parts = prompt.messages.first().parts
        assertEquals(listOf("need a tool"), (parts.first() as MessagePart.Reasoning).content)
        assertIs<MessagePart.Tool.Call>(parts.last())
        assertEquals(history, converter.entries(prompt.messages))
    }

    @Test
    fun googleSignatureMarkerSharesAssistantWithItsCall() {
        val converter = SessionMessages(scriptedModel { _, _ -> emptyFlow() }.copy(reasoningFormat = "google", reasoningInContent = true))
        val history = listOf(
            SessionEntry.Reasoning("", encrypted = "google-signature", format = "google"),
            SessionEntry.ToolCall("call", "lookup", "{}"),
        )
        val prompt = converter.prompt("", history)
        val parts = prompt.messages.single().parts
        val marker = parts.first() as MessagePart.Reasoning
        assertTrue(marker.content.isEmpty())
        assertEquals("google-signature", marker.encrypted)
        assertIs<MessagePart.Tool.Call>(parts.last())
        assertEquals(history, converter.entries(prompt.messages))
    }

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

    @Test
    fun textAttachmentsAreTextOnEveryReplay() {
        val model = scriptedModel { _, _ -> emptyFlow() }.copy(attachments = { "marker42".encodeToByteArray() })
        val converter = SessionMessages(model)
        val history = listOf(SessionEntry.UserMessage("Read this", attachments = listOf(
            Attachment("text", "note.md", "text/plain", 8),
            Attachment("json", "data.json", "application/json", 8),
        )))
        for (entries in listOf(history, history + SessionEntry.AssistantMessage("ok") + SessionEntry.UserMessage("Read it again"))) {
            val parts = converter.prompt("", entries).messages.first().parts
            assertTrue(parts.all { it is MessagePart.Text })
            assertEquals("Read this\n\nFile: note.md\nmarker42\n\n\n\nFile: data.json\nmarker42\n\n", parts.joinToString("") { (it as MessagePart.Text).text })
        }
    }
}
