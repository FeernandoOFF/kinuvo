package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.WebKit.*
import platform.darwin.NSObject

internal actual val visualEditing = true

private class EditHandler(val onMessage: () -> ((String) -> Unit)?) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        if (didReceiveScriptMessage.frameInfo.mainFrame) (didReceiveScriptMessage.body as? String)?.let { onMessage()?.invoke(it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?) {
    val current by rememberUpdatedState(onMessage)
    UIKitView(
        factory = {
            WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                if (onMessage != null) userContentController.addScriptMessageHandler(EditHandler { current }, "kinuvo")
            })
        },
        modifier = modifier,
        update = { it.loadHTMLString(html, baseURL = null) },
        onRelease = { if (onMessage != null) it.configuration.userContentController.removeScriptMessageHandlerForName("kinuvo") },
        // Cooperative mode delays touches, which keeps WebKit from focusing editable text.
        properties = UIKitInteropProperties(interactionMode = if (onMessage != null) UIKitInteropInteractionMode.NonCooperative else UIKitInteropInteractionMode.Cooperative()),
    )
}
