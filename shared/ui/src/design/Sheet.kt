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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.scrimColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalSheetDrag = staticCompositionLocalOf<Modifier> { Modifier }

/** Whether the content is in a [BottomSheet], e.g. to center its title. */
val LocalInSheet = staticCompositionLocalOf { false }

/** A 90% height sheet. Swiping down or tapping outside requests dismissal, allowing a draft guard to refuse. */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    footer: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).imePadding()) {
            val height = maxHeight * 0.9f
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
                    .semantics { dismiss { onDismissRequest(); true } }
                    // Swallows taps so they don't reach the scrim.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                val dragModifier = Modifier.draggable(
                    rememberDraggableState { d -> scope.launch { drag.snapTo(drag.value + d) } },
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (drag.value > threshold || velocity > 1500f) onDismissRequest()
                        drag.animateTo(0f)
                    },
                )
                Column(Modifier.fillMaxWidth().then(dragModifier)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp).size(48.dp, 20.dp),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(36.dp, 4.dp).background(color(mutedColor).copy(alpha = 0.5f), CircleShape)) }
                    header()
                }
                CompositionLocalProvider(LocalSheetDrag provides dragModifier, LocalInSheet provides true) {
                    Column(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)), content = content)
                }
                footer?.let { Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(bottom = 12.dp)) { it() } }
            }
        }
    }
}
