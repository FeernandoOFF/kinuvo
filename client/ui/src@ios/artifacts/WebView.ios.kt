package kinuvo.ui.artifacts

import kinuvo.ui.design.keepNativeTouches
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * One in-memory store for every page while the app runs, so a reopened page loads its scripts, styles and fonts from
 * cache; nothing is written to disk.
 */
internal val pageData: WKWebsiteDataStore by lazy { WKWebsiteDataStore.nonPersistentDataStore() }

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?, channel: PageChannel?, editing: Boolean) {
    val current by rememberUpdatedState(onMessage)
    val loaded = remember { arrayOf<String?>(null) }
    UIKitView(
        factory = {
            WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration().apply {
                websiteDataStore = pageData
                if (onMessage != null) userContentController.addScriptMessageHandler(EditHandler { current }, "kinuvo")
            }).also {
                // The page handles its own panning and zooming.
                if (editing) it.scrollView.apply {
                    scrollEnabled = false
                    bounces = false
                    minimumZoomScale = 1.0
                    maximumZoomScale = 1.0
                    pinchGestureRecognizer?.enabled = false
                }
            }
        },
        modifier = modifier.keepNativeTouches(),
        update = { channel?.send = { message -> it.evaluateJavaScript(pageDelivery(message), null) }
            if (loaded[0] != html) { loaded[0] = html; it.loadHTMLString(html, baseURL = null) } },
        onRelease = { channel?.send = {}
            if (onMessage != null) it.configuration.userContentController.removeScriptMessageHandlerForName("kinuvo") },
        // Cooperative mode delays touches, which keeps WebKit from focusing editable text.
        properties = UIKitInteropProperties(interactionMode = if (editing) UIKitInteropInteractionMode.NonCooperative else UIKitInteropInteractionMode.Cooperative()),
    )
}
