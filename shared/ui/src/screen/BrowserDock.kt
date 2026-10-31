package kinuvo.ui.screen

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.VerticalSeparator
import kinuvo.app.navigation.Route
import kinuvo.app.screen.LiveViewModel
import kinuvo.protocol.ScreenRef
import kinuvo.ui.navigation.PageAction
import kinuvo.ui.session.BrowserDock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Lays [chat] beside its Agent's Browser on wide windows: toggled from the chat's header, resized by dragging its
 * edge, and expanded to the full Browser page by [onExpand]. Narrow, [chat] gets no dock and opens the page instead.
 */
@Composable
fun BrowserDockHost(browser: Route.Browser?, key: String, onExpand: (Route.Browser) -> Unit, chat: @Composable (BrowserDock?) -> Unit) {
    var open by rememberSaveable(browser) { mutableStateOf(false) }
    var focus by remember(browser) { mutableStateOf<Pair<ScreenRef, String?>?>(null) }
    var width by rememberSaveable { mutableStateOf(520f) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dock = if (browser != null && maxWidth >= 760.dp) BrowserDock(open, { open = !open }) { ref, reason -> focus = ref to reason; open = true } else null
        val max = (maxWidth - 360.dp).value.coerceAtLeast(360f)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { chat(dock) }
            if (dock != null && open) {
                val live = koinViewModel<LiveViewModel>(key = "$key/$browser") { parametersOf(browser!!.copy(screen = focus?.first, reason = focus?.second)) }
                LaunchedEffect(live, focus) { focus?.let { live.focus(it.first, it.second) } }
                Box(Modifier.width(7.dp).fillMaxHeight().pointerHoverIcon(PointerIcon.Hand)
                    .draggable(rememberDraggableState { delta -> width = (width - with(density) { delta.toDp().value }).coerceIn(360f, max) }, Orientation.Horizontal),
                    contentAlignment = Alignment.Center) { VerticalSeparator() }
                Box(Modifier.width(width.coerceIn(360f, max).dp).fillMaxHeight()) {
                    LiveViewScreen(live, null, docked = true, extra = listOf(
                        PageAction(Lucide.Maximize2, "Expand Browser") { onExpand(browser!!.copy(screen = live.state.value.selected)) },
                        PageAction(Lucide.X, "Close Browser") { open = false },
                    ))
                }
            }
        }
    }
}
