package kinuvo.ui.agent

import kinuvo.app.agent.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.AgentInfo
import kinuvo.ui.design.*
import kotlin.random.Random

internal const val BundledAvatar = "files/avatars.riv"

/** The Agent's color: its avatar's ink. */
internal val AgentInfo.ink: Color get() = AvatarInk[AvatarArtboards.indexOf(avatarArtboard)]

/**
 * An Agent's avatar: its Rive animation in [state], or the first letter of its name on [background] when it has none.
 */
@Composable
fun AgentAvatar(agent: AgentInfo?, size: Dp, modifier: Modifier = Modifier, background: Color = color(accentColor), gaze: Gaze = Gaze.Forward, state: AvatarState = AvatarState.Idle) {
    val artboard = agent?.avatarArtboard
    if (artboard != null) RiveAvatar(BundledAvatar, modifier.size(size), gaze, artboard, state = state)
    else Box(modifier.size(size).background(background, Theme[shapes][smallShape]), contentAlignment = Alignment.Center) {
        Text(agent?.name?.take(1)?.uppercase() ?: "·", color = color(onAccentColor), fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold)
    }
}
