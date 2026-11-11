package kinuvo.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager

@Suppress("DEPRECATION")
@Composable
internal actual fun rememberScreenClipboard(): suspend () -> String? {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { clipboard.getText()?.text } }
}
