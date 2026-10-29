package kinuvo.ui.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Google's code scanner: its own camera screen, so the app needs no camera permission. */
@Composable
internal actual fun rememberQrScanner(onScanned: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scanned by rememberUpdatedState(onScanned)
    return remember(context) {
        {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan().addOnSuccessListener { code -> code.rawValue?.let(scanned) }
        }
    }
}
