package kinuvo.ui.session

import kinuvo.app.session.*

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kinuvo.protocol.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import dev.nucleusframework.window.tao.TaoDragAndDropPayload
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.io.File
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException

internal actual fun attachmentImage(bytes: ByteArray, fullSize: Boolean): ImageBitmap? = Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual val hasCamera = false

@Composable
internal actual fun rememberAttachmentPicker(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (AttachmentSource) -> Unit {
    val read = rememberFileReader(onResult)
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    return remember { { source ->
        scope.launch {
            try {
                val photos = source != AttachmentSource.Files
                val files = FileKit.openFilePicker(
                    type = if (photos) FileKitType.Image else FileKitType.File(),
                    mode = FileKitMode.Multiple(),
                    dialogSettings = FileKitDialogSettings(title = if (photos) "Choose images" else "Choose files"),
                )
                if (!files.isNullOrEmpty()) read(files.map { it.file })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { result(emptyList(), e.message) }
        }
    } }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.attachmentDrop(enabled: Boolean, onResult: (List<UploadAttachmentParams>, String?) -> Unit, onHover: (Boolean) -> Unit): Modifier {
    val read = rememberFileReader(onResult)
    val hover by rememberUpdatedState(onHover)
    val accepting by rememberUpdatedState(enabled)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                if (!accepting) return false
                val files = (event.nativeEvent as? TaoDragAndDropPayload)?.files.orEmpty()
                if (files.isEmpty()) return false
                read(files.map(::File))
                return true
            }
        }
    }
    // Always attached, so a drag already in progress still ends and clears the hover when [enabled] changes.
    return dragAndDropTarget(shouldStartDragAndDrop = { accepting && it.nativeEvent is TaoDragAndDropPayload }, target = target)
}

@Composable
private fun rememberFileReader(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (List<File>) -> Unit {
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    return remember { { files ->
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    require(files.size <= MAX_ATTACHMENTS) { "Choose up to 4 files" }
                    files.map { f ->
                        require(f.isFile && f.length() in 1..MAX_ATTACHMENT_BYTES.toLong()) { "Files must be between 1 byte and 10 MB" }
                        UploadAttachmentParams(f.name, Base64.encode(f.readBytes()))
                    }
                }
                result(selected, null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { result(emptyList(), e.message) }
        }
    } }
}

@Composable
internal actual fun rememberAttachmentSaver(onError: (String) -> Unit): (AttachmentData) -> Unit {
    val scope = rememberCoroutineScope()
    val error by rememberUpdatedState(onError)
    return remember { { data ->
        scope.launch {
            try {
                val file = FileKit.openFileSaver(suggestedName = data.attachment.name, defaultExtension = null) ?: return@launch
                withContext(Dispatchers.IO) { file.file.writeBytes(Base64.decode(data.base64)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Couldn't save attachment") }
        }
    } }
}
