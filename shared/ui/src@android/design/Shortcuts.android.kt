package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val usesCommandKey: Boolean = false
internal actual val hasTouchKeyboard: Boolean = true

actual fun Modifier.mouseHistoryButtons(onBack: () -> Unit, onForward: () -> Unit): Modifier = this

@Composable
actual fun BrowserBackHandler() {}
