package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.browser.window
import org.w3c.dom.events.Event

internal actual val usesCommandKey: Boolean = "Mac" in window.navigator.platform || "iP" in window.navigator.platform
internal actual val hasTouchKeyboard: Boolean = window.navigator.maxTouchPoints > 0

/** The browser's back and forward buttons go through [BrowserBackHandler] instead. */
actual fun Modifier.mouseHistoryButtons(onBack: () -> Unit, onForward: () -> Unit): Modifier = this

@Composable
actual fun BrowserBackHandler() {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    DisposableEffect(dispatcher) {
        val input = BrowserBackInput()
        dispatcher.addInput(input)
        onDispose { dispatcher.removeInput(input) }
    }
}

/**
 * Keeps one extra browser history entry while the app can go back, so the browser's Back pops it and goes back in the
 * app instead of leaving the page; with nothing to go back to, the entry is removed and Back leaves as usual.
 */
private class BrowserBackInput : NavigationEventInput() {
    private var enabled = false
    private var trapped = false
    /** Pops this made itself, still to arrive; the trap waits for them so it isn't popped by one. */
    private var echoes = 0
    private val onPopState: (Event) -> Unit = {
        when {
            echoes > 0 -> echoes--
            trapped -> {
                trapped = false
                dispatchOnBackCompleted()
            }
            // Forward onto the entry this left behind.
            else -> trapped = true
        }
        sync()
    }

    override fun onAdded(dispatcher: NavigationEventDispatcher) = window.addEventListener("popstate", onPopState)

    override fun onRemoved() {
        window.removeEventListener("popstate", onPopState)
        enabled = false
        sync()
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        enabled = hasEnabledHandlers
        sync()
    }

    private fun sync() {
        if (echoes > 0 || enabled == trapped) return
        trapped = enabled
        if (enabled) window.history.pushState(null, "")
        else {
            echoes++
            window.history.back()
        }
    }
}
