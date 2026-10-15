package kinuvo.ui.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap
import ui.generated.resources.Res

/** Where the avatar looks: [x] and [y] are 0-100 with 50 centred, matching the `lookX`/`lookY` inputs of the Avatar state machine. */
enum class Gaze(val x: Float, val y: Float) { Forward(50f, 50f), Down(50f, 100f), Up(50f, 0f), Left(0f, 50f), Right(100f, 50f) }

val LocalRiveGaze = compositionLocalOf { Gaze.Forward }

internal const val AvatarMachine = "Avatar"

/**
 * Plays [artboard] from the Rive [file] with an `Avatar` state machine, looking at [gaze].
 * Each change of [poke] fires the `poke` trigger. Where Rive can't run (iOS, web, and desktops other than Apple silicon) it shows [RiveStill].
 */
@Composable
internal expect fun RiveAvatar(file: String, modifier: Modifier = Modifier, gaze: Gaze = Gaze.Forward, artboard: String? = null, poke: Int = 0)

/** The bundled still of [artboard]; the avatar bundle has one PNG per artboard. */
@Composable
internal fun RiveStill(file: String, modifier: Modifier, artboard: String?) {
    val image by produceState<ImageBitmap?>(null, file, artboard) {
        value = runCatching { Res.readBytes(if (file == "files/avatars.riv" && artboard != null) "files/avatar-${artboard.lowercase().replace(" ", "-")}.png" else file.removeSuffix(".riv") + ".png").decodeToImageBitmap() }.getOrNull()
    }
    image?.let { Image(it, contentDescription = null, modifier = modifier) }
}
