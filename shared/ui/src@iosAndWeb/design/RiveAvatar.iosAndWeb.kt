package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kinuvo.app.agent.AvatarState

@Composable
internal actual fun RiveAvatar(file: String, modifier: Modifier, gaze: Gaze, artboard: String?, poke: Int, state: AvatarState) = RiveStill(file, modifier, artboard, state)
