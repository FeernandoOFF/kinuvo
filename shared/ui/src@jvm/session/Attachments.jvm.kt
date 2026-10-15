package kinuvo.ui.session

import kinuvo.app.session.*

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kinuvo.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import kotlin.io.encoding.Base64

internal actual fun attachmentImage(bytes: ByteArray, fullSize: Boolean): ImageBitmap? = Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual val hasCamera = false

@Composable
internal actual fun rememberAttachmentPicker(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (AttachmentSource) -> Unit {
    val read = rememberFileReader(onResult)
    return remember { { source ->
        val photos = source != AttachmentSource.Files
        val dialog = FileDialog(null as Frame?, if (photos) "Choose images" else "Choose files", FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (photos) dialog.setFilenameFilter { _, name -> attachmentMimeType(name)?.startsWith("image/") == true }
        dialog.isVisible = true
        val files = dialog.files
        dialog.dispose()
        if (files.isNotEmpty()) read(files.toList())
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
                val files = (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
                read(files.map { File(URI(it)) })
                return true
            }
        }
    }
    // Always attached, so a drag already in progress still ends and clears the hover when [enabled] changes.
    return dragAndDropTarget(shouldStartDragAndDrop = { accepting && it.dragData() is DragData.FilesList }, target = target)
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { result(emptyList(), e.message) }
        }
    } }
}

@Composable
internal actual fun rememberAttachmentSaver(onError: (String) -> Unit): (AttachmentData) -> Unit {
    val scope = rememberCoroutineScope()
    val error by rememberUpdatedState(onError)
    return remember { { data ->
        val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE)
        dialog.file = data.attachment.name
        dialog.isVisible = true
        val name = dialog.file
        val directory = dialog.directory
        dialog.dispose()
        if (name != null) scope.launch {
            try { withContext(Dispatchers.IO) { File(directory, name).writeBytes(Base64.decode(data.base64)) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Couldn't save attachment") }
        }
    } }
}
