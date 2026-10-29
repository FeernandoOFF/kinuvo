package kinuvo.ui.connection

import androidx.compose.runtime.Composable

/** Opens the camera to scan a QR code and gives [onScanned] its text; null where this device can't scan. */
@Composable
internal expect fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)?
