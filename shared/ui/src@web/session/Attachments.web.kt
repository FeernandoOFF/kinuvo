package kinuvo.ui.session

import kinuvo.app.session.*

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kinuvo.protocol.AttachmentData
import kinuvo.protocol.UploadAttachmentParams
import kotlinx.browser.document
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import org.w3c.files.get

internal actual fun attachmentImage(bytes: ByteArray, fullSize: Boolean): ImageBitmap? = bytes.decodeToImageBitmap()

internal actual val hasCamera = false

@Composable
internal actual fun Modifier.attachmentDrop(enabled: Boolean, onResult: (List<UploadAttachmentParams>, String?) -> Unit, onHover: (Boolean) -> Unit): Modifier = this

/** A hidden file input; each file is read as a data URL, whose base64 tail is the upload. */
@OptIn(ExperimentalWasmJsInterop::class)
@Composable
internal actual fun rememberAttachmentPicker(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (AttachmentSource) -> Unit {
    val result by rememberUpdatedState(onResult)
    return remember { { source ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.multiple = true
        if (source != AttachmentSource.Files) input.accept = "image/*"
        input.onchange = {
            val files = input.files
            val count = files?.length ?: 0
            val read = mutableListOf<UploadAttachmentParams>()
            for (i in 0 until count) {
                val file = files!![i]!!
                val reader = FileReader()
                reader.onload = {
                    read += UploadAttachmentParams(file.name, reader.result.toString().substringAfter("base64,"))
                    if (read.size == count) result(read.toList(), null)
                }
                reader.onerror = { result(emptyList(), "Couldn't read ${file.name}") }
                reader.readAsDataURL(file)
            }
        }
        input.click()
    } }
}

/** Downloads the attachment through a data URL link. */
@Composable
internal actual fun rememberAttachmentSaver(onError: (String) -> Unit): (AttachmentData) -> Unit = remember { { data ->
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = "data:${data.attachment.mimeType};base64,${data.base64}"
    link.download = data.attachment.name
    link.click()
} }
