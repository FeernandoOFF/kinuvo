package kinuvo.ui.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** The camera's QR code scanner, implemented in Swift with VisionKit. */
interface QrScanner {
    val available: Boolean
    /** Completes on the main thread with the code's text, or null when cancelled. */
    fun scan(completion: (String?) -> Unit)
}

private var qrScanner: QrScanner? = null
fun installQrScanner(scanner: QrScanner) { qrScanner = scanner }

@Composable
internal actual fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)? {
    val scanned by rememberUpdatedState(onScanned)
    val scanner = qrScanner?.takeIf { it.available } ?: return null
    return remember(scanner) { { scanner.scan { text -> text?.let(scanned) } } }
}
