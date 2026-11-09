package kinuvo.ui.design

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kinuvo.app.surface.SurfaceImages
import kinuvo.ui.session.attachmentImage
import org.koin.mp.KoinPlatform
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

/** Data URIs decode here; web images come through the Server, like Surface images. */
internal object MarkdownImages : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val images = LocalSurfaceImages.current ?: remember { KoinPlatform.getKoinOrNull()?.getOrNull<SurfaceImages>() }
        val bitmap by produceState<ImageBitmap?>(null, link, images) {
            value = try {
                val base64 = if (link.startsWith("data:image/")) link.substringAfter("base64,", "").ifEmpty { null }
                    else images?.takeIf { link.startsWith("https://") || link.startsWith("http://") }?.get(link)?.base64
                base64?.let { attachmentImage(Base64.decode(it), fullSize = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        return bitmap?.let { ImageData(BitmapPainter(it), Modifier.widthIn(max = 480.dp), contentScale = ContentScale.Fit) }
    }
}
