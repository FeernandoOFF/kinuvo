package kinuvo.protocol

import kotlinx.serialization.Serializable

const val MAX_ATTACHMENTS = 4
const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
const val MAX_ATTACHMENT_TOTAL_BYTES = 20 * 1024 * 1024

val ATTACHMENT_MIME_TYPES = setOf(
    "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
    "text/csv", "application/json", "text/plain",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

fun attachmentInput(mimeType: String) = when {
    mimeType.startsWith("image/") -> "image"
    mimeType.startsWith("text/") || mimeType == "application/json" -> "text"
    else -> "file"
}

@Serializable
data class Attachment(val id: String, val name: String, val mimeType: String, val size: Int)

@Serializable
data class UploadAttachmentParams(val name: String, val base64: String)

@Serializable
data class AttachmentRef(val id: String)

@Serializable
data class AttachmentData(val attachment: Attachment, val base64: String)

fun attachmentMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt", "md", "log", "tsv", "xml", "html", "css", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "py", "rb", "rs", "go", "swift", "c", "h", "cpp", "sh", "yaml", "yml", "toml", "sql" -> "text/plain"
    else -> null
}
