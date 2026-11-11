package kinuvo.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import kinuvo.app.surface.SurfaceImages
import kinuvo.ui.session.attachmentImage
import kotlinx.coroutines.CancellationException
import org.intellij.markdown.MarkdownElementTypes.LINK_DESTINATION
import org.intellij.markdown.MarkdownElementTypes.LINK_TEXT
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.koin.mp.KoinPlatform
import kotlin.io.encoding.Base64

private sealed interface Loaded {
    data object Loading : Loaded
    data object Failed : Loaded
    data class Ready(val bitmap: ImageBitmap) : Loaded
}

/** Data URIs decode here; web images come through the Server, like Surface images. */
@Composable
private fun loadImage(link: String): Loaded {
    val images = LocalSurfaceImages.current ?: remember { KoinPlatform.getKoinOrNull()?.getOrNull<SurfaceImages>() }
    return produceState<Loaded>(Loaded.Loading, link, images) {
        value = try {
            val base64 = if (link.startsWith("data:image/")) link.substringAfter("base64,", "").ifEmpty { null }
                else images?.takeIf { link.startsWith("https://") || link.startsWith("http://") }?.get(link)?.base64
            base64?.let { attachmentImage(Base64.decode(it), fullSize = true) }?.let(Loaded::Ready) ?: Loaded.Failed
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Loaded.Failed }
    }.value
}

internal object MarkdownImages : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = (loadImage(link) as? Loaded.Ready)?.let {
        ImageData(BitmapPainter(it.bitmap), Modifier.widthIn(max = 480.dp), contentScale = ContentScale.Fit)
    }
}

/** A standalone image; one that can't load becomes a link, so the reply is never blank. */
@Composable
internal fun MarkdownImageBlock(content: String, node: ASTNode) {
    val link = node.find(LINK_DESTINATION)?.getUnescapedTextInNode(content).orEmpty()
    val alt = node.find(LINK_TEXT)?.getUnescapedTextInNode(content)?.trim('[', ']').orEmpty()
    val uri = LocalUriHandler.current
    when (val image = loadImage(link)) {
        is Loaded.Ready -> Image(image.bitmap, alt, Modifier.widthIn(max = 480.dp).fillMaxWidth()
            .aspectRatio(image.bitmap.width.toFloat() / image.bitmap.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
        Loaded.Loading -> Box(Modifier.size(120.dp, 80.dp), contentAlignment = Alignment.Center) {
            Icon(Lucide.Image, Modifier.size(24.dp), contentDescription = alt, tint = color(mutedColor))
        }
        Loaded.Failed -> Row(Modifier.clickable(enabled = link.startsWith("http")) { runCatching { uri.openUri(link) } },
            horizontalArrangement = Arrangement.spacedBy(Spacing.space8), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.ImageOff, Modifier.size(18.dp), tint = color(mutedColor))
            Text(alt.ifBlank { link.substringAfterLast('/').ifBlank { "Image" } }, color = color(accentColor))
        }
    }
}

private fun ASTNode.find(type: IElementType): ASTNode? = if (this.type == type) this else children.firstNotNullOfOrNull { it.find(type) }
