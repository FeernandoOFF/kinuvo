package kinuvo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController

@Composable
internal actual fun rememberShare(): ((String) -> Unit)? = remember { { text -> present(text) } }

@Composable
internal actual fun rememberShareFile(): ((String) -> Unit)? = remember { { path -> present(NSURL.fileURLWithPath(path)) } }

@OptIn(ExperimentalForeignApi::class)
private fun present(item: Any) {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    top?.let { host ->
        val sheet = UIActivityViewController(listOf(item), null)
        // iPads show it as a popover, which needs an anchor.
        sheet.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            host.view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        }
        host.presentViewController(sheet, animated = true, completion = null)
    }
}
