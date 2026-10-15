package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun RiveAvatar(file: String, modifier: Modifier, gaze: Gaze, artboard: String?, poke: Int) = RiveStill(file, modifier, artboard)
