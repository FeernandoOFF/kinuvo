package kinuvo.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import com.composables.ui.components.Text
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class Shown(val title: String?, val content: @Composable () -> Unit, val onDismiss: () -> Unit)

/** Where [Popover]s show: over the whole app, which blurs behind them while [active]. */
@Stable
class Popovers {
    private var shown by mutableStateOf<Shown?>(null)
    val active get() = shown != null

    internal fun show(title: String?, content: @Composable () -> Unit, onDismiss: () -> Unit) { shown = Shown(title, content, onDismiss) }
    internal fun hide() { shown = null }

    /** Touch screens get a draggable sheet; pointers get a centered panel. */
    @Composable
    fun Host() {
        var last by remember { mutableStateOf<Shown?>(null) }
        shown?.let { last = it }
        AnimatedVisibility(shown != null, enter = fadeIn(), exit = fadeOut()) {
            val popover = last ?: return@AnimatedVisibility
            val touch = LocalInteractionMode.current == InteractionMode.Touch
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = shown != null) { popover.onDismiss() }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(color(backgroundColor).copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = popover.onDismiss))
                val drag = remember(popover) { Animatable(0f) }
                val scope = rememberCoroutineScope()
                val threshold = with(LocalDensity.current) { 96.dp.toPx() }
                Box(Modifier.align(if (touch) Alignment.BottomCenter else Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
                    .widthIn(max = if (touch) Dp.Infinity else 440.dp).fillMaxWidth()
                    .animateEnterExit(enter = slideInVertically { if (touch) it else 0 }, exit = slideOutVertically { if (touch) it else 0 })
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        enabled = touch,
                        onDragStopped = { v -> if (drag.value > threshold || v > 1500f) popover.onDismiss() else drag.animateTo(0f) },
                    )) {
                    Panel(Modifier.fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(12.dp)) {
                        if (touch) Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).size(36.dp, 4.dp).background(color(mutedColor).copy(alpha = 0.4f), CircleShape))
                        popover.title?.let { Text(it, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), fontSize = 16.sp) }
                        popover.content()
                    }
                }
            }
        }
    }
}

val LocalPopovers = staticCompositionLocalOf<Popovers?> { null }

/** Shows [content] over the app while [visible], in a sheet on touch screens or a centered panel for pointers. */
@Composable
fun Popover(visible: Boolean, onDismiss: () -> Unit, title: String? = null, content: @Composable () -> Unit) {
    val host = LocalPopovers.current ?: return
    val latest by rememberUpdatedState(content)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, visible) {
        if (visible) host.show(title, { latest() }, { dismiss() })
        onDispose { if (visible) host.hide() }
    }
}
