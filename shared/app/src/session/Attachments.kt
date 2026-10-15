package kinuvo.app.session

import kinuvo.protocol.*

data class PendingAttachment(val id: String, val upload: UploadAttachmentParams, val uploaded: Attachment? = null) {
    val attachment get() = Attachment(id, upload.name, attachmentMimeType(upload.name).orEmpty(), upload.base64.length / 4 * 3 - upload.base64.takeLast(2).count { it == '=' })
}

/** An attachment open in the preview; [base64] is null while it loads. */
data class AttachmentPreview(val attachment: Attachment, val base64: String?)


enum class AttachmentSource { Camera, Photos, Files }
