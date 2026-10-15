package kinuvo.ui.session

import kinuvo.app.session.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.LocalContentColor
import kinuvo.ui.design.accentColor
import kinuvo.ui.design.color
import kinuvo.ui.design.markdownTextStyle

/** Voice chat's controls in place of the composer: pause and resume, then [settings], the speaker and end. */
@Composable
internal fun VoiceControls(
    state: VoiceChatState,
    onPause: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    settings: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PauseButton(state, onPause)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = settings)
            IconButton(onClick = { onSpeaker(!state.speaker) }, modifier = Modifier.size(52.dp), style = ButtonStyle.Secondary, shape = CircleShape) {
                Icon(if (state.speaker) Lucide.Volume2 else Lucide.VolumeX, Modifier.size(22.dp), contentDescription = if (state.speaker) "Mute replies" else "Read replies aloud")
            }
            IconButton(onClick = onEnd, modifier = Modifier.size(52.dp).background(color(onBackgroundColor), CircleShape), style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(backgroundColor)) {
                Icon(Lucide.X, Modifier.size(24.dp), contentDescription = "End voice chat")
            }
        }
    }
}

/** Pauses the conversation, cutting any reply short, and resumes it; its ring breathes while listening. */
@Composable
private fun PauseButton(state: VoiceChatState, onPause: (Boolean) -> Unit) {
    val listening = state.phase == VoicePhase.Listening && !state.paused
    val breath by rememberInfiniteTransition().animateFloat(1f, 1.12f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val ring by animateColorAsState(if (listening) color(accentColor).copy(alpha = 0.6f) else Color.Transparent)
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(76.dp).scale(if (listening) breath else 1f).border(2.dp, ring, CircleShape))
        IconButton(onClick = { onPause(!state.paused) }, modifier = Modifier.size(76.dp), style = ButtonStyle.Secondary, shape = CircleShape,
            contentColor = color(if (state.paused) accentColor else onBackgroundColor)) {
            Icon(if (state.paused) Lucide.Mic else Lucide.Pause, Modifier.size(30.dp), contentDescription = if (state.paused) "Resume" else "Pause")
        }
    }
}

/** What voice chat is doing. */
@Composable
internal fun VoiceStatus(state: VoiceChatState, modifier: Modifier = Modifier) {
    Box(modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp)) {
        val status = state.error ?: if (state.paused) "Paused" else when (state.phase) {
            VoicePhase.Off, VoicePhase.Starting -> "Starting…"
            VoicePhase.Listening -> "Listening"
            VoicePhase.Thinking -> "Thinking"
            VoicePhase.Speaking -> "Speaking"
        }
        Text(status, fontSize = 15.sp, color = color(if (state.error != null) destructiveColor else mutedColor), textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A warm light rising from the bottom edge, brightest while the Agent speaks. */
@Composable
internal fun VoiceGlow(state: VoiceChatState, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition().animateFloat(0.7f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val level by animateFloatAsState(when {
        state.paused || state.error != null -> 0.08f
        state.phase == VoicePhase.Speaking -> 0.4f
        state.phase == VoicePhase.Listening -> 0.25f
        else -> 0.15f
    }, tween(600))
    val accent = color(accentColor)
    // Screens sit inside the safe area; the light reaches past it to the screen's edges.
    val insets = WindowInsets.safeDrawing
    Box(modifier.fillMaxWidth().height(320.dp).layout { measurable, constraints ->
        val left = insets.getLeft(this, layoutDirection)
        val right = insets.getRight(this, layoutDirection)
        val bottom = insets.getBottom(this)
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + left + right, constraints.maxHeight + bottom))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-left, 0) }
    }.background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = level * pulse)))))
}

/** A reply being read aloud, as it's spoken: its text up to [reached] in full color, the rest dimmed. */
@Composable
internal fun SpokenText(text: String, reached: Int, modifier: Modifier = Modifier) {
    val plain = remember(text) { speakable(text) }
    val split = remember(text, reached) { speakable(text.take(reached)).length.coerceAtMost(plain.length) }
    val spoken = LocalContentColor.current
    val rest = color(mutedColor)
    BasicText(buildAnnotatedString {
        withStyle(SpanStyle(color = spoken)) { append(plain.take(split)) }
        withStyle(SpanStyle(color = rest)) { append(plain.drop(split)) }
    }, modifier, style = markdownTextStyle())
}
