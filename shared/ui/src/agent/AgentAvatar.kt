package kinuvo.ui.agent

import kinuvo.app.agent.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.composables.ui.components.Text
import com.composables.ui.components.Icon
import com.composables.icons.lucide.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.ExecutionKind
import kinuvo.ui.design.*

internal const val BundledAvatar = "files/avatars.riv"

/** The Agent's color: its avatar's ink. */
internal val AgentInfo.ink: Color get() = AvatarInk[AvatarArtboards.indexOf(avatarArtboard)]

/**
 * An Agent's avatar: its Rive animation in [state], or the first letter of its name on [background] when it has none.
 */
@Composable
fun AgentAvatar(agent: AgentInfo?, size: Dp, modifier: Modifier = Modifier, background: Color = color(accentColor), gaze: Gaze = Gaze.Forward, state: AvatarState = AvatarState.Idle) {
    Box(modifier.size(size)) {
        val artboard = agent?.avatarArtboard
        if (artboard != null) RiveAvatar(BundledAvatar, Modifier.size(size), gaze, artboard, state = state)
        else Box(Modifier.size(size).background(background, Theme[shapes][smallShape]), contentAlignment = Alignment.Center) {
            Text(agent?.name?.take(1)?.uppercase() ?: "·", color = color(onAccentColor), fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold)
        }
        if (agent != null && agent.external) {
            val remote = agent.executionKind == ExecutionKind.Remote
            val badgeSize = (size * 0.4f).coerceIn(8.dp, 20.dp)
            val tint = when {
                agent.readiness.online -> mutedColor
                agent.readiness.reasonCode in setOf("not_found", "unsupported") -> destructiveColor
                else -> warningColor
            }
            Box(Modifier.align(Alignment.BottomEnd).size(badgeSize)
                .background(color(panelColor), CircleShape).border(1.dp, color(backgroundColor), CircleShape)
                .semantics { stateDescription = if (agent.readiness.online) "Ready" else agent.readiness.reason ?: "Unavailable" },
                contentAlignment = Alignment.Center) {
                Icon(if (remote) Lucide.Server else Lucide.Terminal, Modifier.size(badgeSize * 0.7f), tint = color(tint),
                    contentDescription = if (remote) "Remote Server" else "External harness")
            }
        }
    }
}
