package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed

/** Whether shortcuts use ⌘ rather than Ctrl. */
internal expect val usesCommandKey: Boolean

/** The platform's shortcut modifier (⌘ on Apple platforms, Ctrl elsewhere) is held. */
val KeyEvent.isShortcutPressed get() = if (usesCommandKey) isMetaPressed else isCtrlPressed

/** Label for a shortcut, e.g. `shortcut("N")` → "⌘N" or "Ctrl+N". */
fun shortcut(key: String, shift: Boolean = false): String =
    if (usesCommandKey) (if (shift) "⇧" else "") + "⌘$key" else (if (shift) "Ctrl+Shift+" else "Ctrl+") + key

/** Calls [onBack] and [onForward] for a mouse's back and forward buttons, where the platform reports them. */
expect fun Modifier.mouseHistoryButtons(onBack: () -> Unit, onForward: () -> Unit): Modifier

/** Routes the platform's own back button, where the app's back handlers don't already get it, to them. */
@Composable
expect fun BrowserBackHandler()
