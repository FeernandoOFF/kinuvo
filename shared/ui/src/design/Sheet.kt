package kinuvo.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.scrimColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A phone sheet over the dimmed screen: medium height or [tall], toggled by its grabber or by dragging its [header]
 * up and down. Dragging it down from medium, or tapping the scrim, asks to dismiss through [onDismissRequest], which may
 * refuse, e.g. to ask about unsaved changes. [footer] stays above the keyboard and the home indicator while the content
 * scrolls between.
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    footer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var tall by rememberSaveable { mutableStateOf(false) }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).imePadding()) {
            val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val height by animateDpAsState(if (tall || keyboard) maxHeight - 8.dp else maxHeight * 0.66f)
            Box(Modifier.fillMaxSize().background(color(scrimColor))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismissRequest))
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val threshold = with(LocalDensity.current) { 80.dp.toPx() }
            val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            Column(
                // Clear of side insets, e.g. a status area at the edge of a foldable, which would take taps on the header.
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).fillMaxWidth().height(height)
                    .animateEnterExit(enter = slideInVertically { it }, exit = slideOutVertically { it })
                    .offset { IntOffset(0, drag.value.roundToInt().coerceAtLeast(0)) }
                    .shadow(16.dp, shape).clip(shape).background(color(backgroundColor), shape)
                    // Swallows taps so they don't reach the scrim.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(
                    Modifier.fillMaxWidth().draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo(drag.value + d) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            val moved = drag.value
                            when {
                                moved < -threshold / 2 || v < -1500f -> tall = true
                                (moved > threshold || v > 1500f) && tall -> tall = false
                                moved > threshold || v > 1500f -> onDismissRequest()
                            }
                            drag.animateTo(0f)
                        },
                    ),
                ) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp).size(48.dp, 20.dp)
                            .clickable { tall = !tall }
                            .semantics { role = Role.Button; contentDescription = "Resize sheet"; stateDescription = if (tall) "Expanded" else "Half height" },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(36.dp, 4.dp).background(color(mutedColor).copy(alpha = 0.5f), CircleShape)) }
                    header()
                }
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                footer?.let { Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(bottom = 12.dp)) { it() } }
            }
        }
    }
}
