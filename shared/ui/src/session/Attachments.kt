package kinuvo.ui.session

import kinuvo.app.session.*

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.mediumShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kinuvo.protocol.*
import kinuvo.ui.design.Markdown
import kinuvo.ui.design.MonoStyle
import kinuvo.ui.design.Spinner
import kinuvo.ui.design.color
import kinuvo.ui.design.Panel
import kotlin.io.encoding.Base64

/** A preview of an attached image; [fullSize] keeps every pixel, e.g. for Live view, where Android otherwise decodes a thumbnail. */
internal expect fun attachmentImage(bytes: ByteArray, fullSize: Boolean = false): ImageBitmap?

internal expect val hasCamera: Boolean

@Composable
internal expect fun rememberAttachmentPicker(onResult: (List<UploadAttachmentParams>, String?) -> Unit): (AttachmentSource) -> Unit

/** Attaches files dropped on the element; [onHover] says whether files are dragged over it. A no-op where dropping isn't supported. */
@Composable
internal expect fun Modifier.attachmentDrop(enabled: Boolean, onResult: (List<UploadAttachmentParams>, String?) -> Unit, onHover: (Boolean) -> Unit): Modifier

@Composable
internal expect fun rememberAttachmentSaver(onError: (String) -> Unit): (AttachmentData) -> Unit

private fun Attachment.image(base64: String?) =
    if (base64 != null && mimeType.startsWith("image/")) runCatching { attachmentImage(Base64.decode(base64)) }.getOrNull() else null

private val Attachment.text get() = mimeType.startsWith("text/") || mimeType == "application/json"

@Composable
internal fun AttachmentChip(attachment: Attachment, base64: String? = null, onPreview: (() -> Unit)? = null, onOpen: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val preview = remember(attachment.id, base64) { attachment.image(base64) }
    val shape = Theme[shapes][mediumShape]
    Panel(onPreview?.let { Modifier.clip(shape).clickable(onClick = it) } ?: Modifier, shape = shape, contentPadding = PaddingValues(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) Image(preview, attachment.name, Modifier.size(52.dp))
            else Icon(Lucide.File, Modifier.size(22.dp))
            Column(Modifier.widthIn(max = 150.dp)) {
                Text(attachment.name, fontSize = 12.sp, singleLine = true, overflow = TextOverflow.Ellipsis)
                Text(attachment.sizeLabel, color = color(mutedColor), fontSize = 11.sp)
            }
            onOpen?.let { IconButton(onClick = it, style = ButtonStyle.Ghost, modifier = Modifier.size(28.dp)) { Icon(Lucide.Download, Modifier.size(16.dp), contentDescription = "Save ${attachment.name}") } }
            onRemove?.let { IconButton(onClick = it, style = ButtonStyle.Ghost, modifier = Modifier.size(28.dp)) { Icon(Lucide.X, Modifier.size(16.dp), contentDescription = "Remove ${attachment.name}") } }
        }
    }
}

/** Fills its parent with [preview]: images fit and pinch to zoom, text files as text, Markdown rendered; others offer saving. */
@Composable
internal fun AttachmentPreviewer(preview: AttachmentPreview, onSave: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None)) { onClose() }
    val attachment = preview.attachment
    Column(modifier.fillMaxSize().background(color(backgroundColor)).clickable(enabled = false) {}) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onClose, style = ButtonStyle.Ghost, modifier = Modifier.size(40.dp)) { Icon(Lucide.X, Modifier.size(20.dp), contentDescription = "Close") }
            Column(Modifier.weight(1f)) {
                Text(attachment.name, singleLine = true, overflow = TextOverflow.Ellipsis)
                Text(attachment.sizeLabel, color = color(mutedColor), fontSize = 12.sp)
            }
            IconButton(onClick = onSave, style = ButtonStyle.Ghost, modifier = Modifier.size(40.dp)) { Icon(Lucide.Download, Modifier.size(20.dp), contentDescription = "Save ${attachment.name}") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val base64 = preview.base64
            val image = remember(attachment.id, base64) { attachment.image(base64) }
            val text = remember(attachment.id, base64) { if (base64 != null && attachment.text) runCatching { Base64.decode(base64).decodeToString().take(MAX_PREVIEW_CHARS) }.getOrNull() else null }
            when {
                base64 == null -> Spinner(size = 24.dp)
                image != null -> {
                    var scale by remember { mutableStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    val zoom = rememberTransformableState { _, change, pan, _ ->
                        scale = (scale * change).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
                    }
                    Image(image, attachment.name, Modifier.fillMaxSize().transformable(zoom)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                }
                text != null -> SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (attachment.name.endsWith(".md", ignoreCase = true)) Markdown(text, Modifier.fillMaxWidth())
                    else Text(text, Modifier.fillMaxWidth(), style = MonoStyle)
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Lucide.File, Modifier.size(40.dp), tint = color(mutedColor))
                    Text("No preview for this file", color = color(mutedColor))
                    Button(onClick = onSave, buttonSize = ButtonSize.Small) { Text("Save") }
                }
            }
        }
    }
}

private val Attachment.sizeLabel get() = if (size < 1024 * 1024) "${(size + 1023) / 1024} KB" else "${size / (1024 * 1024)} MB"

private const val MAX_PREVIEW_CHARS = 200_000
