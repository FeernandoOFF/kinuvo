package kinuvo.ui

import androidx.compose.runtime.Composable

private fun canShare(): Boolean = js("typeof navigator.share === 'function'")

private fun shareText(text: String): Unit = js("navigator.share({ text: text }).catch(function () {})")

@Composable
internal actual fun rememberShare(): ((String) -> Unit)? = if (canShare()) ::shareText else null
