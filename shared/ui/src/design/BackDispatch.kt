package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Sends a back from a shortcut or mouse button through the back handlers, so the innermost one (a popover, an
 * onboarding step) gets it like the system back gesture would; false when none is enabled.
 */
@Composable
fun rememberBackDispatch(): () -> Boolean {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val input = remember { ShortcutBackInput() }
    DisposableEffect(dispatcher) {
        dispatcher?.addInput(input)
        onDispose { dispatcher?.removeInput(input) }
    }
    return input::back
}

private class ShortcutBackInput : NavigationEventInput() {
    private var enabled = false

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) { enabled = hasEnabledHandlers }

    fun back(): Boolean = enabled.also { if (it) dispatchOnBackCompleted() }
}
