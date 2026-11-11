package kinuvo.server

import kinuvo.harness.ToolSpec
import kinuvo.server.plugin.GeneratedImage
import kinuvo.server.plugin.ImageGeneration
import kinuvo.server.tools.GenerateImageTool
import kinuvo.server.plugin.ToolRun
import kinuvo.protocol.Attachment
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GenerateImageToolTest {
    @Test fun loadsSessionReferencesAndAttachesEveryImage() = runTest {
        val reference = Attachment("upload", "input.png", "image/png", 1)
        val attached = mutableListOf<Attachment>()
        val generator = object : ImageGeneration {
            override suspend fun generate(model: String, prompt: String, references: List<GeneratedImage>, size: String?): List<GeneratedImage> {
                assertEquals("image-model", model)
                assertEquals("Edit", prompt)
                assertContentEquals(byteArrayOf(7), references.single().bytes)
                return listOf(GeneratedImage(byteArrayOf(1)), GeneratedImage(byteArrayOf(2), "image/jpeg"))
            }
        }
        val tool = GenerateImageTool(generator, "image-model", { listOf(reference) }, { byteArrayOf(7) })
        val run = object : ToolRun {
            override val tools = emptyList<ToolSpec>()
            override suspend fun call(name: String, args: String): SessionEntry.ToolResult = error("unused")
            override suspend fun attach(name: String, bytes: ByteArray): Attachment =
                Attachment("generated-${attached.size}", name, if (name.endsWith("jpg")) "image/jpeg" else "image/png", bytes.size).also(attached::add)
        }
        val args = buildJsonObject { put("prompt", "Edit"); putJsonArray("references") { add("upload") } }
        val result = tool.execute(args, "call", run)
        assertEquals(listOf("generated-1.png", "generated-2.jpg"), attached.map { it.name })
        assertTrue(result.contains("generated-0"))
        assertFailsWith<IllegalArgumentException> {
            tool.execute(buildJsonObject { put("prompt", "Edit"); putJsonArray("references") { add("other-session") } }, "call", run)
        }
        assertEquals(2, attached.size)
    }
}
