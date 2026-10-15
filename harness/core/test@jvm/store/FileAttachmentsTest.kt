package kinuvo.harness.core.store

import kinuvo.protocol.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*

class FileAttachmentsTest {
    @Test
    fun persistsBytesSeparatelyFromMessages() {
        val home = Files.createTempDirectory("attachments").toFile()
        try {
            val store = FileAttachments(Path(home.path))
            val content = "Name,Value\nSleep,8\n"
            val a = store.upload(UploadAttachmentParams("journal.csv", Base64.encode(content.encodeToByteArray())))
            assertEquals("text/csv", a.mimeType)
            assertEquals(content, FileAttachments(Path(home.path)).bytes(a).decodeToString())
            val message = SessionEntry.UserMessage("", attachments = listOf(a))
            val json = ProtocolJson.encodeToString(SessionEntry.serializer(), message)
            assertFalse(json.contains(Base64.encode(content.encodeToByteArray())))
            assertEquals(message, ProtocolJson.decodeFromString(SessionEntry.serializer(), json))
        } finally { home.deleteRecursively() }
    }

    @Test
    fun rejectsPathsForgedMetadataOversizeAndBinaryText() {
        val home = Files.createTempDirectory("attachments").toFile()
        try {
            val store = FileAttachments(Path(home.path))
            fun upload(name: String, bytes: ByteArray = "ok".encodeToByteArray()) = store.upload(UploadAttachmentParams(name, Base64.encode(bytes)))
            assertFailsWith<IllegalArgumentException> { upload("../private.txt") }
            assertFailsWith<IllegalArgumentException> { store.get("../private") }
            assertFailsWith<IllegalArgumentException> { upload("program.exe") }
            assertFailsWith<IllegalArgumentException> { upload("binary.txt", byteArrayOf(0, 1)) }
            assertFailsWith<IllegalArgumentException> { upload("large.txt", ByteArray(256 * 1024 + 1) { 65 }) }
            assertFailsWith<IllegalArgumentException> { upload("fake.pdf") }
            assertFailsWith<IllegalArgumentException> { store.upload(UploadAttachmentParams("large.pdf", "A".repeat((MAX_ATTACHMENT_BYTES + 2) / 3 * 4 + 4))) }
            val a = upload("note.md")
            assertFailsWith<IllegalArgumentException> { store.bytes(a.copy(name = "forged.md")) }
        } finally { home.deleteRecursively() }
    }
}
