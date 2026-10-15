package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Where the avatar looks: [x] and [y] are 0-100 with 50 centred, matching the `lookX`/`lookY` inputs of the Avatar state machine. */
enum class Gaze(val x: Float, val y: Float) { Forward(50f, 50f), Down(50f, 100f), Up(50f, 0f), Left(0f, 50f), Right(100f, 50f) }

val LocalRiveGaze = compositionLocalOf { Gaze.Forward }

internal const val AvatarMachine = "Avatar"

/**
 * Plays [artboard] from the Rive [file] with an `Avatar` state machine, looking at [gaze].
 * Each change of [poke] fires the `poke` trigger. iOS and web show a bundled still instead; the avatar bundle has one PNG per artboard.
 */
@Composable
internal expect fun RiveAvatar(file: String, modifier: Modifier = Modifier, gaze: Gaze = Gaze.Forward, artboard: String? = null, poke: Int = 0)
