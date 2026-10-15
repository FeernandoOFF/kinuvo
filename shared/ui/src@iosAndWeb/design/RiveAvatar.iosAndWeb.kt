package kinuvo.ui.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap
import ui.generated.resources.Res

@Composable
internal actual fun RiveAvatar(file: String, modifier: Modifier, gaze: Gaze, artboard: String?, poke: Int) {
    val image by produceState<ImageBitmap?>(null, file, artboard) {
        value = runCatching { Res.readBytes(if (file == "files/avatars.riv" && artboard != null) "files/avatar-${artboard.lowercase().replace(" ", "-")}.png" else file.removeSuffix(".riv") + ".png").decodeToImageBitmap() }.getOrNull()
    }
    image?.let { Image(it, contentDescription = null, modifier = modifier) }
}
