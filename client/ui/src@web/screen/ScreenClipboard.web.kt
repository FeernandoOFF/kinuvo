package kinuvo.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window
import kotlinx.coroutines.await

@Composable
internal actual fun rememberScreenClipboard(): suspend () -> String? = remember {
    { window.navigator.clipboard.readText().await().toString() }
}
