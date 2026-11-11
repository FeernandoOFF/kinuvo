package kinuvo.harness.builtin

import ai.koog.prompt.message.MessagePart
import kinuvo.testing.harness.scriptedModel
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.surface.SurfaceAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.*

class SessionMessagesTest {
    @Test fun backgroundReportsRemainOrdinaryHistory() {
        val report = SessionEntry.ToolReport("shell", "call", "done")
        val history = listOf(report, report, SessionEntry.UserMessage("continue"))
        assertEquals(history, history.activeHistory())
        assertEquals(listOf(history.last()), history.latestTurn())
        assertTrue(history.activations().isEmpty())
        assertEquals(listOf(SessionEntry.AssistantMessage("summary")), (history + SessionEntry.Compaction(history = listOf(SessionEntry.AssistantMessage("summary")))).activeHistory())
        val prompt = messages.prompt("", listOf(report))
        assertEquals("Background work from shell (call call) finished:\ndone", prompt.messages.single().parts.filterIsInstance<MessagePart.Text>().single().text)
        assertEquals(listOf(report), messages.entries(prompt.messages))
    }

    @Test fun surfaceActionContextReplaysIntoModelInput() {
        val context = JsonObject(mapOf("name" to JsonPrimitive("Alex")))
        val action = SurfaceAction("call", "plan", "save", "submit", context, context)
        val entry = SessionEntry.UserMessage("Save plan", action = action)
        for (history in listOf(listOf(entry), listOf(entry, SessionEntry.AssistantMessage("Saved"), SessionEntry.UserMessage("Continue")))) {
            val prompt = messages.prompt("", history)
            val text = prompt.messages.first().parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }
            assertTrue(text.startsWith("Save plan"))
            assertTrue("Surface action: save" in text)
            assertTrue("surface: plan" in text)
            assertTrue("Context: $context" in text)
            assertEquals(entry, messages.entries(prompt.messages).first())
        }
    }

    @Test
    fun toolScreenshotsDoNotRequireAnAttachmentReaderForTextOnlyModels() {
        val model = scriptedModel { _, _ -> emptyFlow() }.copy(attachments = null)
        val screenshot = Attachment("image", "browser.jpg", "image/jpeg", 20)
        val result = SessionEntry.ToolResult("open", "browser_open", "LinkedIn URL is open; semantic page text", attachments = listOf(screenshot))
        val prompt = SessionMessages(model).prompt("", listOf(result))
        assertTrue(prompt.messages.flatMap { it.parts }.none { it is MessagePart.Attachment })
        val tool = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single()
        assertTrue("semantic page text" in tool.output)
        assertTrue("screenshot omitted" in tool.output)
        assertEquals(listOf(screenshot), result.attachments)
    }

    private val messages = SessionMessages(scriptedModel { _, _ -> emptyFlow() })

    @Test
    fun compatibleEndpointsSkipOpenAIReasoningOnProviderSwitch() {
        val model = scriptedModel { _, _ -> emptyFlow() }
        val history = listOf(
            SessionEntry.Reasoning("summary", "reasoning-id", "openai-ciphertext"),
            SessionEntry.AssistantMessage("answer"),
            SessionEntry.UserMessage("continue"),
        )
        val compatible = SessionMessages(model.copy(replayReasoning = false)).prompt("", history)
        assertTrue(compatible.messages.flatMap { it.parts }.none { it is MessagePart.Reasoning })
        assertEquals(listOf("answer", "continue"), compatible.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().map { it.text })
        val openai = SessionMessages(model).prompt("", history)
        assertEquals("openai-ciphertext", openai.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Reasoning>().single().encrypted)
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
    fun imageIdsAreListedOnlyWithImageGeneration() {
        val model = scriptedModel { _, _ -> emptyFlow() }.copy(attachments = { byteArrayOf(1) })
        val history = listOf(SessionEntry.UserMessage("Edit", attachments = listOf(Attachment("f", "f.png", "image/png", 1))))
        fun text(references: Boolean) = SessionMessages(model, references).prompt("", history).messages.single().parts.filterIsInstance<MessagePart.Text>().joinToString { it.text }
        assertEquals("Edit", text(false))
        assertEquals("Edit\n\n$IMAGE_REFERENCES\nf: f.png", text(true))
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
