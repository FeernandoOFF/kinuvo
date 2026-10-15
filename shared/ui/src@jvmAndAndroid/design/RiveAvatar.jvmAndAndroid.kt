package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.muazkadan.rivecmp.CustomRiveAnimation
import dev.muazkadan.rivecmp.RiveCompositionSpec
import dev.muazkadan.rivecmp.rememberRiveComposition
import dev.muazkadan.rivecmp.utils.ExperimentalRiveCmpApi
import ui.generated.resources.Res

/** Initializes Rive; false where it has no native library. */
@Composable
internal expect fun initializeRive(): Boolean

@OptIn(ExperimentalRiveCmpApi::class)
@Composable
internal actual fun RiveAvatar(file: String, modifier: Modifier, gaze: Gaze, artboard: String?, poke: Int) {
    if (!initializeRive()) return RiveStill(file, modifier, artboard)
    val composition by rememberRiveComposition(file, artboard) {
        RiveCompositionSpec.byteArray(Res.readBytes(file))
    }
    Box(modifier) {
        key(artboard) {
            CustomRiveAnimation(modifier = Modifier.matchParentSize(), composition = composition, stateMachineName = AvatarMachine, artboardName = artboard)
        }
        // Android's Rive view takes touches itself; this cover hits first, so taps reach whatever the avatar sits in.
        Box(Modifier.matchParentSize().pointerInput(Unit) {})
    }
    LaunchedEffect(composition, gaze) {
        composition?.run {
            setNumberInput(AvatarMachine, "lookX", gaze.x)
            setNumberInput(AvatarMachine, "lookY", gaze.y)
        }
    }
    LaunchedEffect(composition, poke) { if (poke > 0) composition?.setTriggerInput(AvatarMachine, "poke") }
}
