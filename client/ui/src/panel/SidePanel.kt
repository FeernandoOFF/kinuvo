package kinuvo.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.app.panel.PanelTab
import kinuvo.app.panel.SidePanelState
import kinuvo.app.panel.SidePanelViewModel
import kinuvo.ui.design.*

/** Keeps each open tab's ViewModels apart, so a Browser stays live while another tab is shown, and ends them when it closes. */
class PanelStores {
    private val stores = mutableMapOf<PanelTab, ViewModelStore>()

    fun owner(tab: PanelTab): ViewModelStoreOwner {
        val store = stores.getOrPut(tab) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    /** Ends the ViewModels of tabs no longer open. */
    fun retain(tabs: List<PanelTab>) = (stores.keys - tabs.toSet()).forEach { stores.remove(it)?.clear() }

    fun clear() = retain(emptyList())
}

@Composable
fun rememberPanelStores(key: Any?): PanelStores {
    val stores = remember(key) { PanelStores() }
    DisposableEffect(stores) { onDispose { stores.clear() } }
    return stores
}

/** A tab's place in the strip. */
class PanelTabLabel(val icon: ImageVector, val label: String)

/** Actions on the panel's tabs; [onClose] can ask first, e.g. about unsaved Agent settings. */
class SidePanelActions(
    val onSelect: (PanelTab) -> Unit = {},
    val onClose: (PanelTab) -> Unit = {},
    val onCollapse: (Boolean) -> Unit = {},
    val onResize: (width: Float, max: Float) -> Unit = { _, _ -> },
)

fun SidePanelViewModel.actions(onClose: (PanelTab) -> Unit = ::close) =
    SidePanelActions(::select, onClose, ::collapse, ::resize)

/**
 * The window's Side panel on wide layouts: the shown tab, a strip of every open tab along the window's edge, and an edge
 * to drag between them and the screen. Tabs' header actions lead, away from window buttons. Dragging it narrow, or double-clicking it, collapses the panel to its strip.
 */
@Composable
fun SidePanel(state: SidePanelState, actions: SidePanelActions, maxWidth: Dp, label: (PanelTab) -> PanelTabLabel, content: @Composable (PanelTab) -> Unit) {
    val density = LocalDensity.current
    val max = maxWidth.value
    var dragged by remember { mutableStateOf(0f) }
    val current by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    Row(Modifier.fillMaxHeight().semantics { paneTitle = "Side panel" }) {
        val edge = remember { MutableInteractionSource() }
        val onEdge by edge.collectIsHoveredAsState()
        var dragging by remember { mutableStateOf(false) }
        Box(Modifier.width(9.dp).fillMaxHeight().hoverable(edge).pointerHoverIcon(ResizeHorizontalIcon)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentActions.onCollapse(!current.collapsed) }) }
            .draggable(rememberDraggableState { delta ->
                dragged -= with(density) { delta.toDp().value }
                actions.onResize(dragged, max)
            }, Orientation.Horizontal, onDragStarted = { dragging = true; dragged = if (current.collapsed) 0f else current.width.coerceAtMost(max) },
                onDragStopped = { dragging = false }),
            contentAlignment = Alignment.Center) {
            val active = onEdge || dragging
            Box(Modifier.width(if (active) 2.dp else 1.dp).fillMaxHeight().background(color(if (active) accentColor else borderColor)))
            if (active) Box(Modifier.size(5.dp, 32.dp).background(color(accentColor), CircleShape))
        }
        val selected = state.selected
        if (!state.collapsed && selected != null) {
            Box(Modifier.width(state.width.coerceIn(0f, max.coerceAtLeast(0f)).dp).fillMaxHeight()) {
                CompositionLocalProvider(LocalLeadingActions provides true) { key(selected) { content(selected) } }
            }
            VerticalSeparator()
        }
        TabStrip(state, actions, label)
    }
}

@Composable
private fun TabStrip(state: SidePanelState, actions: SidePanelActions, label: (PanelTab) -> PanelTabLabel) {
    val chrome = LocalWindowChrome.current?.takeIf { it.trailingInset > 0.dp }
    Column(
        Modifier.width(StripWidth).fillMaxHeight().background(color(sidebarColor))
            .padding(top = (chrome?.height ?: 0.dp) + Spacing.space8, bottom = Spacing.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.space4),
    ) {
        HeaderButton(if (state.collapsed) Lucide.PanelRightOpen else Lucide.PanelRightClose,
            if (state.collapsed) "Expand panel" else "Collapse panel", { actions.onCollapse(!state.collapsed) })
        HorizontalSeparator(Modifier.width(24.dp).padding(vertical = Spacing.space4))
        state.tabs.forEach { tab -> StripTab(label(tab), state.showing(tab), { actions.onSelect(tab) }, { actions.onClose(tab) }) }
    }
}

@Composable
private fun StripTab(tab: PanelTabLabel, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.width(StripWidth).height(36.dp).hoverable(hover)) {
        if (selected) Box(Modifier.align(Alignment.CenterEnd).width(4.dp).height(24.dp)
            .background(color(onBackgroundColor), RoundedCornerShape(topStart = Radius.extraSmall, bottomStart = Radius.extraSmall)))
        Box(Modifier.align(Alignment.Center)) { Hint(tab.label, TooltipSide.Start) {
            Box(Modifier.size(32.dp).titleBarControl().clip(Theme[shapes][mediumShape])
                .background(if (selected) color(subtleColor) else color(sidebarColor))
                .clickable(role = Role.Tab, onClickLabel = tab.label, onClick = onSelect), contentAlignment = Alignment.Center) {
                Icon(tab.icon, Modifier.size(17.dp), tint = color(if (selected) onBackgroundColor else mutedColor), contentDescription = tab.label)
            }
        } }
        if (hovered) Box(Modifier.align(Alignment.TopEnd).padding(end = 2.dp).size(14.dp).clip(CircleShape).background(color(borderColor))
            .clickable(role = Role.Button, onClickLabel = "Close ${tab.label}", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Lucide.X, Modifier.size(10.dp), tint = color(onBackgroundColor), contentDescription = "Close ${tab.label}")
        }
    }
}

/** Width of the tab strip along the window's edge, all a collapsed panel keeps. */
val StripWidth = 44.dp
