package kinuvo.ui.onboarding

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.app.settings.*
import kinuvo.app.onboarding.*
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
import kinuvo.ui.session.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.LocalTextStyle
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun Floating(artboard: String, size: Dp, gaze: Gaze, poke: Int, phase: Float, modifier: Modifier = Modifier) {
    val bob by rememberInfiniteTransition().animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(3800, easing = LinearEasing)))
    val pop = remember { Animatable(1f) }
    LaunchedEffect(poke) { if (poke > 0) { pop.animateTo(1.06f, tween(110)); pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 320f)) } }
    RiveAvatar(BundledAvatar, modifier.size(size).graphicsLayer {
        translationY = sin(bob + phase) * 5.dp.toPx(); scaleX = pop.value; scaleY = pop.value
    }, gaze, artboard, poke)
}

@Composable
internal fun SpeechBubble(text: String, modifier: Modifier, shape: RoundedCornerShape) {
    Box(modifier.clip(shape).background(color(panelColor)).border(1.dp, color(borderColor), shape).padding(horizontal = 16.dp, vertical = 12.dp)) {
        AnimatedContent(text, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) }) { Text(it, style = TextStyles.callout, lineHeight = 21.sp) }
    }
}

@Composable
internal fun Cta(action: OnboardingAction, busy: Boolean, modifier: Modifier, icon: ImageVector? = null) {
    val enabled = action.enabled && !busy
    val ink by animateColorAsState(color(onBackgroundColor).copy(alpha = if (enabled) 1f else 0.3f))
    Row(modifier.height(56.dp).clip(CircleShape).background(ink).clickable(enabled, role = Role.Button, onClick = action.run).padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (busy) { Spinner(tint = color(backgroundColor)); Spacer(Modifier.width(10.dp)) }
        else icon?.let { Icon(it, Modifier.size(18.dp), tint = color(backgroundColor)); Spacer(Modifier.width(10.dp)) }
        AnimatedContent(action.label, transitionSpec = { fadeIn() togetherWith fadeOut() }) {
            Text(it, color = color(backgroundColor), fontWeight = FontWeight.Bold, style = TextStyles.title)
        }
    }
}

@Composable
internal fun displayStyle(size: TextUnit) =
    LocalTextStyle.current.copy(fontSize = size, lineHeight = size * 1.1f, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
