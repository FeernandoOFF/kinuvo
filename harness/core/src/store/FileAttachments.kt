package kinuvo.harness.core.store

import kinuvo.protocol.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

class FileAttachments(private val dir: Path) {
    fun upload(p: UploadAttachmentParams): Attachment {
        require(p.name.isNotBlank() && p.name.length <= 200 && p.name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid file name" }
        val mime = requireNotNull(attachmentMimeType(p.name)) { "Unsupported file type: ${p.name}" }
        require(p.base64.length <= (MAX_ATTACHMENT_BYTES + 2) / 3 * 4) { "Files must be 10 MB or smaller" }
        val bytes = Base64.decode(p.base64)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ATTACHMENT_BYTES) { "Files must be between 1 byte and 10 MB" }
        if (mime.startsWith("text/") || mime == "application/json") {
            require(bytes.size <= 256 * 1024) { "Text files must be 256 KB or smaller" }
            bytes.decodeToString(throwOnInvalidSequence = true)
            require(0.toByte() !in bytes) { "This file isn't plain text" }
        }
        if (mime == "application/pdf") require(bytes.take(5).toByteArray().decodeToString() == "%PDF-") { "This file isn't a PDF" }
        if (mime == "image/webp") require(bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP") { "This file isn't a WebP image" }
        val a = Attachment(Uuid.random().toString(), p.name, mime, bytes.size)
        dir.createDirectories()
        val data = data(a.id)
        createPrivateFile(data)
        try {
            SystemFileSystem.sink(data).buffered().use { it.write(bytes) }
            writeAtomically(meta(a.id), ProtocolJson.encodeToString(Attachment.serializer(), a), private = true)
        } catch (e: Throwable) {
            SystemFileSystem.delete(data, mustExist = false)
            throw e
        }
        return a
    }

    fun get(id: String): Attachment = meta(id).readTextOrNull()?.let { ProtocolJson.decodeFromString(Attachment.serializer(), it) }
        ?: error("Attachment is no longer available")

    fun bytes(a: Attachment): ByteArray {
        require(get(a.id) == a) { "Attachment details don't match" }
        return SystemFileSystem.source(data(a.id)).buffered().use { it.readByteArray() }
    }

    fun download(id: String): AttachmentData = get(id).let { AttachmentData(it, Base64.encode(bytes(it))) }

    private fun meta(id: String) = Path(dir, "${checked(id)}.json")
    private fun data(id: String) = Path(dir, "${checked(id)}.data")
    private fun checked(id: String): String {
        require(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(id)) { "Invalid attachment ID" }
        return id
    }
}
