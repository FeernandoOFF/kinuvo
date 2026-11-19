package kinuvo.ui.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * Native window chrome the app draws under, e.g. a desktop title bar with the traffic lights on top of our content.
 * The top [height] drags the window except over [titleBarControl] elements.
 */
class WindowChrome(val height: Dp, val leadingInset: Dp, val trailingInset: Dp, val controls: MutableMap<Any, Rect> = mutableMapOf()) {
    /** The same chrome for content placed clear of the window's leading edge. */
    val trailingOnly by lazy { WindowChrome(height, 0.dp, trailingInset, controls) }
    /** The same chrome for content clear of the window's trailing edge. */
    val leadingOnly by lazy { WindowChrome(height, leadingInset, 0.dp, controls) }
    /** The same chrome for content clear of both window edges. */
    val inner by lazy { WindowChrome(height, 0.dp, 0.dp, controls) }

    fun padding(leading: Boolean = true, trailing: Boolean = true) =
        PaddingValues(start = if (leading) leadingInset else Spacing.none, end = if (trailing) trailingInset else Spacing.none)
}

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/** Whether headers put their actions first, as in the Side panel, keeping its corner clear of window buttons. */
val LocalLeadingActions = staticCompositionLocalOf { false }

/** Marks an element inside the title bar as clickable instead of window-dragging. */
fun Modifier.titleBarControl(): Modifier = composed {
    val chrome = LocalWindowChrome.current ?: return@composed this
    val key = remember { Any() }
    DisposableEffect(chrome) { onDispose { chrome.controls.remove(key) } }
    onGloballyPositioned { chrome.controls[key] = Rect(it.positionInWindow(), it.size.toSize()) }
}
