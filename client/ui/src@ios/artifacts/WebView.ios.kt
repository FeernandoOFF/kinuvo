package kinuvo.ui.artifacts

import kinuvo.ui.AudioBytes
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.Foundation.HTTPMethod
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorDomain
import platform.UIKit.UIApplication
import platform.WebKit.*
import platform.darwin.NSObject
import kotlinx.cinterop.ObjCSignatureOverride

internal actual val visualEditing = true

private const val SCHEME = "kinuvo-artifact"

/** A custom scheme gives each Artifact a real origin, `kinuvo-artifact://<id>`, that no other page can reach. */
internal actual fun openOrigin(site: ArtifactSite): ArtifactOrigin? = if (originId(site.id)) ArtifactOrigin("$SCHEME://${site.id}", site) else null

internal actual fun closeOrigin(origin: ArtifactOrigin) = Unit

/** What the web view's loading reads: the origin it serves now. */
private class Serving {
    var origin: ArtifactOrigin? = null
}

private class SiteHandler(val serving: Serving) : NSObject(), WKURLSchemeHandlerProtocol {
    private val scope = MainScope()
    private val running = HashMap<WKURLSchemeTaskProtocol, Job>()

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val request = startURLSchemeTask.request
        val url = request.URL
        val origin = serving.origin
        if (url == null || origin == null) return startURLSchemeTask.didFailWithError(NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorBadURL, null))
        if (request.HTTPMethod != "GET") return deliver(startURLSchemeTask, url, OriginResponse(405, "text/plain", emptyMap(), byteArrayOf()))
        // A task must not be answered once WebKit stopped it, so a stop cancels the fetch behind it.
        running[startURLSchemeTask] = scope.launch {
            val r = origin.serve(url.absoluteString.orEmpty())
            running.remove(startURLSchemeTask)
            deliver(startURLSchemeTask, url, r)
        }
    }

    private fun deliver(task: WKURLSchemeTaskProtocol, url: NSURL, r: OriginResponse) {
        val headers = r.headers + ("Content-Type" to r.contentType) + ("Content-Length" to r.body.size.toString())
        task.didReceiveResponse(NSHTTPURLResponse(url, r.status.toLong(), "HTTP/1.1", buildMap<Any?, Any?> { putAll(headers) }))
        task.didReceiveData(AudioBytes.toData(r.body))
        task.didFinish()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        running.remove(stopURLSchemeTask)?.cancel()
    }
}

/** Keeps the page at the origin: anything else is cancelled, and a link the user taps opens in the browser. */
private class OriginNavigation(val serving: Serving) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL
        val text = url?.absoluteString.orEmpty()
        if (text.startsWith("about:") || serving.origin?.owns(text) == true) return decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        if (url != null && decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated && url.scheme in setOf("http", "https", "mailto")) {
            UIApplication.sharedApplication.openURL(url)
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }
}

private class EditHandler(val onMessage: () -> ((String) -> Unit)?) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        if (didReceiveScriptMessage.frameInfo.mainFrame) (didReceiveScriptMessage.body as? String)?.let { onMessage()?.invoke(it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?, channel: PageChannel?, editing: Boolean, origin: ArtifactOrigin?) {
    val current by rememberUpdatedState(onMessage)
    val serving = remember { Serving() }
    serving.origin = origin
    // The web view holds its navigation delegate weakly.
    val navigation = remember { OriginNavigation(serving) }
    val loaded = remember { arrayOf<String?>(null) }
    UIKitView(
        factory = {
            WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                if (onMessage != null) userContentController.addScriptMessageHandler(EditHandler { current }, "kinuvo")
                if (origin != null) setURLSchemeHandler(SiteHandler(serving), SCHEME)
            }).also {
                if (origin != null) it.navigationDelegate = navigation
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
        onRelease = { channel?.send = {}; serving.origin = null
            if (onMessage != null) it.configuration.userContentController.removeScriptMessageHandlerForName("kinuvo") },
        // Cooperative mode delays touches, which keeps WebKit from focusing editable text.
        properties = UIKitInteropProperties(interactionMode = if (editing) UIKitInteropInteractionMode.NonCooperative else UIKitInteropInteractionMode.Cooperative()),
    )
}
