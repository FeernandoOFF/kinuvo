package kinuvo.harness.codex

import kinuvo.harness.testing.*
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class CodexAttachmentsLiveTest {
    @Test
    fun readsImagesPdfAndTextFilesThroughChatGptLogin() {
        if (System.getenv("KINUVO_LIVE_ATTACHMENTS") != "1") return
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color.RED; fillRect(0, 0, 64, 64); dispose() } }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val text = "The marker is lotus42.".encodeToByteArray()
        val pdf = pdf("The marker is orchid73.")
        val bytes = mapOf("image" to png, "text" to text, "pdf" to pdf)
        val config = AgentConfig(model = System.getenv("KINUVO_MODEL") ?: "gpt-5.6-sol", reasoning = "low")
        val model = openAIModel(config, codexLLMClient(CodexAuth()), info = kinuvo.protocol.ModelInfo(config.model, input = listOf("text", "image", "file"))) { bytes.getValue(it.id) }
        runBlocking {
            for ((id, name, mime, question, expected) in listOf(
                listOf("image", "red.png", "image/png", "What is the image's dominant color? One word.", "red"),
                listOf("text", "note.txt", "text/plain", "What marker is inside the attached file? Reply with only the marker.", "lotus42"),
                listOf("pdf", "note.pdf", "application/pdf", "What marker is inside the attached PDF? Reply with only the marker.", "orchid73"),
            )) {
                val a = Attachment(id, name, mime, bytes.getValue(id).size)
                val history = listOf(SessionEntry.UserMessage(question, attachments = listOf(a)))
                val answer = StringBuilder()
                model.request("Read the attachments and answer tersely.", history, emptyList()).collect { if (it is StreamFrame.TextDelta) answer.append(it.text) }
                assertTrue(expected in answer.toString().lowercase(), "$name: $answer")
                println("Verified $mime")
            }
        }
    }

    private fun pdf(text: String): ByteArray {
        val content = "BT /F1 16 Tf 40 150 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, obj -> out.length.also { out.append("${i + 1} 0 obj\n$obj\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }
}
