package kinuvo.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIColor
import platform.WebKit.*
import platform.darwin.NSObject

private class HtmlHandler(val bridge: HtmlBridge) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        if (didReceiveScriptMessage.frameInfo.mainFrame) (didReceiveScriptMessage.body as? String)?.let(bridge::receive)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    val handler = remember(bridge) { HtmlHandler(bridge) }
    UIKitView(factory = {
        WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            userContentController.addScriptMessageHandler(handler, "kinuvoNative")
        }).apply {
            scrollView.scrollEnabled = false
            // Transparent over the chat, and never zoomed: pinches and double taps stay with the page.
            opaque = false
            backgroundColor = UIColor.clearColor
            scrollView.backgroundColor = UIColor.clearColor
            scrollView.minimumZoomScale = 1.0
            scrollView.maximumZoomScale = 1.0
            scrollView.bouncesZoom = false
            scrollView.pinchGestureRecognizer?.enabled = false
            bridge.post = { evaluateJavaScript(htmlSurfaceDelivery(it), null) }
            loadHTMLString(document, baseURL = null)
        }
    }, modifier = modifier, onRelease = {
        bridge.post = {}
        it.stopLoading()
        it.configuration.userContentController.removeScriptMessageHandlerForName("kinuvoNative")
        it.loadHTMLString("", baseURL = null)
    })
}

internal actual val htmlSurfacePlatform = "mobile"
internal actual fun htmlSurfaceLocale(): String = NSLocale.currentLocale.localeIdentifier.replace('_', '-')
