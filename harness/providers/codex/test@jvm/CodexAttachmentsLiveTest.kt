package kinuvo.harness.codex

import kinuvo.harness.testing.*
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
        val model = openAIModel(config, codexLLMClient(CodexAuth()), info = ModelInfo(config.model, input = listOf("text", "image"))) { bytes.getValue(it.id) }
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

    @Test
    fun readsOfficeFilesThroughChatGptLogin() {
        if (System.getenv("KINUVO_LIVE_ATTACHMENTS") != "1") return
        val docx = office("word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml", mapOf(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>The marker is lotus42.</w:t></w:r></w:p></w:body></w:document>""",
        ))
        val xlsx = office("xl/workbook.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml", mapOf(
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>The marker is orchid73.</t></is></c></row></sheetData></worksheet>""",
        ))
        val pptx = office("ppt/presentation.xml", "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml", mapOf(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="Text"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>The marker is iris91.</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""",
        ))
        val config = AgentConfig(model = System.getenv("KINUVO_MODEL") ?: "gpt-6.1-sol", reasoning = "low")
        runBlocking {
            for ((name, data, expected) in listOf(Triple("note.docx", docx, "lotus42"), Triple("note.xlsx", xlsx, "orchid73"), Triple("note.pptx", pptx, "iris91"))) {
                val model = openAIModel(config, codexLLMClient(CodexAuth()), ModelInfo(config.model, input = listOf("text", "image"))) { data }
                val attachment = Attachment("file", name, attachmentMimeType(name)!!, data.size)
                val answer = StringBuilder()
                model.request("Read the attachment.", listOf(SessionEntry.UserMessage("What marker is inside the attached file? Reply with only the marker.", attachments = listOf(attachment))), emptyList()).collect {
                    if (it is StreamFrame.TextDelta) answer.append(it.text)
                }
                assertTrue(expected in answer.toString().lowercase(), "$name: $answer")
                println("Verified ${attachment.mimeType}")
            }
        }
    }

    private fun office(main: String, type: String, files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            val types = files.keys.filter { it.endsWith(".xml") }.joinToString("") { path ->
                val mime = when {
                    path == main -> type
                    path.startsWith("ppt/") -> "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
                    else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
                }
                """<Override PartName="/$path" ContentType="$mime"/>"""
            }
            val entries = files + mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>$types</Types>""",
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$main"/></Relationships>""",
            )
            entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.encodeToByteArray()); zip.closeEntry() }
        }
    }.toByteArray()

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
