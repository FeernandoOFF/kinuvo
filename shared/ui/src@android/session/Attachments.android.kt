package kinuvo.ui.session

import kinuvo.app.session.*

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kinuvo.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

internal actual fun attachmentImage(bytes: ByteArray, fullSize: Boolean): ImageBitmap? = decodeImage(bytes, if (fullSize) Int.MAX_VALUE else 256)?.asImageBitmap()

private fun decodeImage(bytes: ByteArray, maxSize: Int): android.graphics.Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / maxSize).coerceAtLeast(1)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal actual val hasCamera = true

@Composable
internal actual fun Modifier.attachmentDrop(enabled: Boolean, onResult: (List<UploadAttachmentParams>, String?) -> Unit, onHover: (Boolean) -> Unit): Modifier = this

/** Camera photos go to a cache file shared through the app's `<package>.attachments` FileProvider, then are deleted. */
@Composable
internal actual fun rememberAttachmentPicker(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (AttachmentSource) -> Unit {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    fun read(uris: List<android.net.Uri>, photo: File? = null) = scope.launch {
        try {
            val files = withContext(Dispatchers.IO) {
                require(uris.size <= MAX_ATTACHMENTS) { "Choose up to 4 files" }
                uris.map { uri ->
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment"
                    // Camera photos are shrunk below, so only picked files count against the limit as they are.
                    val bytes = requireNotNull(resolver.openInputStream(uri)).use { if (photo != null) it.readBytes() else it.readBytesBounded() }
                    if (photo != null || (resolver.getType(uri)?.startsWith("image/") == true && attachmentMimeType(name) == null)) {
                        val bitmap = requireNotNull(decodeImage(bytes, 2048)) { "Couldn't read photo" }
                        val scale = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
                        val matrix = android.graphics.Matrix().apply { setScale(scale, scale); postRotate(exifRotation(bytes)) }
                        val resized = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        val encoded = java.io.ByteArrayOutputStream().also { resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        if (resized !== bitmap) resized.recycle()
                        bitmap.recycle()
                        UploadAttachmentParams(name.substringBeforeLast('.', name) + ".jpg", Base64.encode(encoded))
                    } else UploadAttachmentParams(name, Base64.encode(bytes))
                }
            }
            result(files, null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { result(emptyList(), e.message ?: "Couldn't read attachment") }
        finally { photo?.delete() }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { read(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { read(it) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = photo?.let(::File)
        photo = null
        if (file == null) return@rememberLauncherForActivityResult
        if (taken) read(listOf(FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)), file) else file.delete()
    }
    return { source ->
        when (source) {
            AttachmentSource.Camera -> try {
                val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "Photo-${System.currentTimeMillis()}.jpg")
                photo = file.path
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.attachments", file))
            } catch (e: android.content.ActivityNotFoundException) { photo = null; result(emptyList(), "No camera app found") }
            AttachmentSource.Photos -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AttachmentSource.Files -> files.launch("*/*")
        }
    }
}

private fun exifRotation(bytes: ByteArray): Float = when (android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0)) {
    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> 0f
}

private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size() + n <= MAX_ATTACHMENT_BYTES) { "Files must be 10 MB or smaller" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

@Composable
internal actual fun rememberAttachmentSaver(onError: (String) -> Unit): (AttachmentData) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val error by rememberUpdatedState(onError)
    var pending by remember { mutableStateOf<AttachmentData?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = pending
        pending = null
        if (uri != null && data != null) scope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(resolver.openOutputStream(uri)).use { it.write(Base64.decode(data.base64)) } } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Couldn't save attachment") }
        }
    }
    return { pending = it; save.launch(it.attachment.name) }
}
