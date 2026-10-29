package kinuvo.ui.connection

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)? = null
