package kinuvo.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import kinuvo.app.surface.SurfaceImages
import kinuvo.ui.session.attachmentImage
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

val LocalSurfaceImages = staticCompositionLocalOf<SurfaceImages?> { null }

@Composable
fun RemoteImage(url: String?, description: String?, modifier: Modifier = Modifier, fit: String? = null, variant: String? = null) {
    val images = LocalSurfaceImages.current
    val bitmap by produceState<ImageBitmap?>(null, url, images) {
        value = null
        if (url != null && images != null) try { value = attachmentImage(Base64.decode(images.get(url).base64)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    Picture(bitmap, description, modifier, fit, variant)
}

@Composable
fun Picture(bitmap: ImageBitmap?, description: String?, modifier: Modifier = Modifier, fit: String? = null, variant: String? = null) {
    val size = when (variant) {
        "icon" -> modifier.size(24.dp)
        "avatar" -> modifier.size(48.dp).clip(CircleShape)
        "smallFeature" -> modifier.size(80.dp)
        "largeFeature", "header" -> modifier.fillMaxWidth().height(200.dp)
        else -> modifier.size(160.dp)
    }
    val image = bitmap
    if (image == null) Box(size) { Icon(Lucide.Image, Modifier.size(24.dp), contentDescription = description ?: "Image unavailable") }
    else Image(image, description, size, contentScale = when (fit) {
        "contain" -> ContentScale.Fit
        "cover" -> ContentScale.Crop
        "none" -> ContentScale.None
        "scaleDown" -> ContentScale.Inside
        else -> ContentScale.FillBounds
    })
}
