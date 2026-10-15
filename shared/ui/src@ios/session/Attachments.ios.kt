package kinuvo.ui.session

import kinuvo.app.session.*

import kinuvo.app.connection.*

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kinuvo.protocol.*
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerSourceType

interface AttachmentPicker {
    /** [source] is `camera`, `photos` or `files`. */
    fun pick(source: String, completion: (String) -> Unit)
    fun save(name: String, base64: String, completion: (String?) -> Unit)
}

private var nativePicker: AttachmentPicker? = null
fun installAttachmentPicker(picker: AttachmentPicker) { nativePicker = picker }

@Serializable
private data class PickerResult(val files: List<UploadAttachmentParams> = emptyList(), val error: String? = null)

internal actual fun attachmentImage(bytes: ByteArray, fullSize: Boolean): ImageBitmap? = Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual val hasCamera get() = UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

@Composable
internal actual fun Modifier.attachmentDrop(enabled: Boolean, onResult: (List<UploadAttachmentParams>, String?) -> Unit, onHover: (Boolean) -> Unit): Modifier = this

@Composable
internal actual fun rememberAttachmentPicker(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (AttachmentSource) -> Unit {
    val result by rememberUpdatedState(onResult)
    return remember { { source ->
        nativePicker?.pick(source.name.lowercase()) { json ->
            runCatching { ProtocolJson.decodeFromString(PickerResult.serializer(), json) }
                .onSuccess { result(it.files, it.error) }
                .onFailure { result(emptyList(), "Couldn't read attachment") }
        } ?: result(emptyList(), "Attachment picker is unavailable")
    } }
}

@Composable
internal actual fun rememberAttachmentSaver(onError: (String) -> Unit): (AttachmentData) -> Unit {
    val error by rememberUpdatedState(onError)
    return remember { { data -> nativePicker?.save(data.attachment.name, data.base64) { if (it != null) error(it) } ?: error("Attachment saving is unavailable") } }
}
