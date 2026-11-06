package kinuvo.ui.voice

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What the orb shows: the call waiting, hearing the user, the Agent working, or the Agent talking. */
enum class OrbMood { Idle, Listening, Thinking, Speaking }

/**
 * A live call's presence, in the Agent's [color]: light drifts inside it, faster while the Agent works, and it swells
 * and glows with [level] (0 to 1), the loudness of whoever is talking. [time] pins the animation, e.g. for screenshots.
 */
@Composable
fun AgentOrb(color: Color, mood: OrbMood, level: Float, modifier: Modifier = Modifier, time: Float? = null) {
    var clock by remember { mutableFloatStateOf(time ?: 0f) }
    if (time == null) LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock = (it - start) / 1e9f }
    }
    val voice by animateFloatAsState(if (mood == OrbMood.Listening || mood == OrbMood.Speaking) level.coerceIn(0f, 1f) else 0f, spring(0.6f, Spring.StiffnessMediumLow))
    val pace by animateFloatAsState(when (mood) { OrbMood.Thinking -> 2.6f; OrbMood.Speaking -> 1.4f; else -> 1f }, spring(stiffness = Spring.StiffnessVeryLow))
    val light = lerp(color, Color.White, 0.62f)
    val deep = lerp(color, Color.Black, 0.45f)
    val warm = lerp(color, Color(0xFFFFC2E2), 0.35f)
    Canvas(modifier) {
        val t = clock * pace
        val breath = 1f + 0.025f * sin(clock * 1.6f)
        val radius = min(size.width, size.height) / 2f * 0.78f * (breath + 0.14f * voice)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.32f + 0.3f * voice), Color.Transparent), center, radius * (1.35f + 0.25f * voice)), radius * 1.6f, center)
        val body = Path().apply { addOval(Rect(center, radius)) }
        clipPath(body) {
            drawCircle(Brush.radialGradient(listOf(lerp(color, Color.White, 0.3f), color, lerp(color, deep, 0.4f)), Offset(center.x - radius * 0.25f, center.y - radius * 0.3f), radius * 1.4f), radius, center)
            fun blob(tint: Color, orbit: Float, speed: Float, phase: Float, scale: Float, alpha: Float) {
                val angle = t * speed + phase
                val at = Offset(center.x + cos(angle) * radius * orbit, center.y + sin(angle * 1.3f) * radius * orbit)
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = alpha), Color.Transparent), at, radius * scale), radius * scale, at)
            }
            blob(light, 0.45f, 0.7f, 0f, 0.75f + 0.25f * voice, 0.7f)
            blob(warm, 0.5f, -0.55f, 2.1f, 0.75f, 0.55f)
            blob(deep, 0.6f, 0.4f, 4.2f, 0.8f, 0.45f)
            blob(Color.White, 0.3f, 1.1f, 1f, 0.45f + 0.25f * voice, 0.35f + 0.3f * voice)
            if (mood == OrbMood.Thinking) for (i in 0 until 3) blob(light, 0.62f, 1.8f, i * 2f * PI.toFloat() / 3f, 0.35f, 0.5f)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, light.copy(alpha = 0.3f)), center, radius), radius, center)
        }
        drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), Offset(center.x - radius, center.y - radius), center), radius, center, style = Stroke(radius * 0.02f))
    }
}
