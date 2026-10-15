package kinuvo.ui.design

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.scrimColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phone layout where [content] slides right to reveal [behind], leaving [peek] of it in view. Swiping right on [content]
 * reveals; swiping left, tapping what's left of [content] or going back (predictively, where supported) covers it again.
 */
@Composable
fun Reveal(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    behind: @Composable () -> Unit,
    enabled: Boolean = true,
    peek: Dp = 48.dp,
    content: @Composable () -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(color(sidebarColor))) {
    val width = (maxWidth - peek).coerceAtLeast(1.dp)
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val edgePx = with(density) { 24.dp.toPx() }
    val flingVelocity = with(density) { 200.dp.toPx() }
    // Content's x offset: 0 when covering, widthPx when revealing.
    var x by remember { mutableFloatStateOf(if (open) widthPx else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Job?>(null) }
    var destination by remember { mutableFloatStateOf(x) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var previousOpen by remember { mutableStateOf(open) }

    fun settle(target: Boolean, velocity: Float = 0f) {
        settling?.cancel()
        destination = if (target) widthPx else 0f
        settling = scope.launch {
            animate(x, destination, velocity, animationSpec = spring(dampingRatio = 1f, stiffness = 400f)) { v, _ ->
                x = v.coerceIn(0f, widthPx)
            }
        }
    }
    LaunchedEffect(open, widthPx) {
        if (open != previousOpen) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            previousOpen = open
        }
        if (!dragging && destination != if (open) widthPx else 0f) settle(open)
    }

    val current by rememberUpdatedState(open)
    val canOpen by rememberUpdatedState(enabled)
    val swipe = Modifier.horizontalSwipe(
        widthPx = widthPx,
        priority = { position -> !current && canOpen && position.x <= edgePx },
        claims = { dx -> dx < 0 && x > 0f || dx > 0 && x < widthPx && canOpen },
        onDown = {
            (settling?.isActive == true).also { interrupted ->
                if (interrupted) { settling?.cancel(); dragging = true }
            }
        },
        onStart = { settling?.cancel(); dragging = true },
        onDrag = { dx -> x = (x + dx).coerceIn(0f, widthPx) },
        onStop = { velocity ->
            dragging = false
            val target = when {
                velocity >= flingVelocity -> true
                velocity <= -flingVelocity -> false
                else -> x > widthPx / 2
            }
            settle(target, velocity)
            if (target != current) onOpenChange(target)
        },
        onCancel = { dragging = false; settle(current) },
    )
    fun progress() = (x / widthPx).coerceIn(0f, 1f)
    fun shape() = RoundedCornerShape(topStart = 56.dp * progress(), bottomStart = 56.dp * progress())
    val border = color(borderColor)

    // Predictive back follows the gesture, sliding content back over behind until it's released or cancelled.
    val back = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(back, isBackEnabled = open, onBackCancelled = { settle(current) }) { onOpenChange(false) }
    LaunchedEffect(back, widthPx) {
        snapshotFlow { (back.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress }.collect { p ->
            if (p != null && current && !dragging) { settling?.cancel(); x = widthPx * (1 - p) }
        }
    }

    Box(Modifier.fillMaxSize().then(swipe)) {
        Box(Modifier.width(width).fillMaxHeight().offset { IntOffset((-widthPx * 0.12f * (1 - progress())).roundToInt(), 0) }) { behind() }
        // Taps on content's empty space would otherwise fall through to behind.
        Box(Modifier.offset { IntOffset(x.roundToInt(), 0) }.fillMaxSize().pointerInput(Unit) {}
            .graphicsLayer { shape = shape(); clip = true; shadowElevation = 12.dp.toPx() * progress() }
            .background(color(backgroundColor)).drawWithContent {
                drawContent()
                if (x > 0f) drawOutline(shape().createOutline(size, layoutDirection, this), border.copy(alpha = border.alpha * progress()), style = Stroke(3.dp.toPx()))
            }) {
            content()
            if (x > 0f) Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = progress() * 0.16f }.background(color(scrimColor))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onOpenChange(false) },
            )
        }
    }
}

/**
 * Edge drags get first refusal; elsewhere, horizontal scrolling and text selection inside the content take priority.
 * Mostly-vertical drags stay with the content.
 */
@Composable
private fun Modifier.horizontalSwipe(
    widthPx: Float,
    priority: (Offset) -> Boolean,
    claims: (dx: Float) -> Boolean,
    onDown: () -> Boolean,
    onStart: () -> Unit,
    onDrag: (dx: Float) -> Unit,
    onStop: (velocity: Float) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val currentPriority by rememberUpdatedState(priority)
    val currentClaims by rememberUpdatedState(claims)
    val currentDown by rememberUpdatedState(onDown)
    val currentStart by rememberUpdatedState(onStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentStop by rememberUpdatedState(onStop)
    val currentCancel by rememberUpdatedState(onCancel)
    return pointerInput(widthPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pass = if (currentPriority(down.position)) PointerEventPass.Initial else PointerEventPass.Main
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            var total = Offset.Zero
            var dragging = false
            var interrupted = currentDown()
            try {
                while (true) {
                    val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPointerInputChange(change)
                    if (dragging) {
                        val dx = change.positionChange().x
                        change.consume()
                        currentDrag(dx)
                        if (!change.pressed) {
                            dragging = false
                            interrupted = false
                            currentStop(tracker.calculateVelocity().x)
                            break
                        }
                        continue
                    }
                    if (!change.pressed || change.isConsumed) break
                    total += change.positionChange()
                    val slop = viewConfiguration.touchSlop
                    if (abs(total.x) > slop && abs(total.x) > abs(total.y) * 1.5f && currentClaims(total.x)) {
                        dragging = true
                        change.consume()
                        currentStart()
                        currentDrag(total.x)
                    } else if (abs(total.y) > slop || abs(total.x) > slop) break
                }
            } finally {
                if (dragging || interrupted) currentCancel()
            }
        }
    }
}
