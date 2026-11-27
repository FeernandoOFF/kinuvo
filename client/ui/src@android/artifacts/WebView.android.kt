package kinuvo.ui.artifacts

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView as AndroidWebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlin.concurrent.Volatile

internal actual val visualEditing = true

/** `.invalid` never resolves and is no registrable domain, so no cookie reaches beyond one Artifact; requests are answered before any lookup. */
internal actual fun openOrigin(site: ArtifactSite): ArtifactOrigin? = if (originId(site.id)) ArtifactOrigin("https://${site.id}.invalid", site) else null

internal actual fun closeOrigin(origin: ArtifactOrigin) = Unit

private class EditBridge(val onMessage: () -> ((String) -> Unit)?) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun edit(html: String) { main.post { onMessage()?.invoke(html) } }
}

private val REASONS = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found")

/** What the web view's request thread reads: the origin it serves now. */
private class Serving {
    @Volatile
    var origin: ArtifactOrigin? = null
}

/** Serves the origin's site and keeps the page there: anything else is blocked, and a link the user taps opens in the browser. */
private class OriginClient(val serving: Serving) : WebViewClient() {
    override fun shouldInterceptRequest(view: AndroidWebView, request: WebResourceRequest): WebResourceResponse? {
        val origin = serving.origin?.takeIf { it.owns(request.url.toString()) } ?: return null
        val r = if (request.method == "GET") origin.respond(request.url.toString()) else null
        return if (r == null) WebResourceResponse("text/plain", "utf-8", 405, "Method Not Allowed", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        else WebResourceResponse(r.mime, if ("charset" in r.contentType) "utf-8" else null, r.status, REASONS[r.status] ?: "Error", r.headers, ByteArrayInputStream(r.body))
    }

    override fun shouldOverrideUrlLoading(view: AndroidWebView, request: WebResourceRequest): Boolean {
        if (serving.origin?.owns(request.url.toString()) == true) return false
        if (request.hasGesture() && request.url.scheme in setOf("http", "https", "mailto")) {
            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return true
    }
}

/** Web storage and cookies of an Artifact's origin end with its view: its state is the Server's. */
private fun forget(base: String) {
    WebStorage.getInstance().deleteOrigin(base)
    val cookies = CookieManager.getInstance()
    cookies.getCookie(base)?.split(";")?.forEach { cookies.setCookie(base, "${it.substringBefore('=').trim()}=; Max-Age=0") }
}

@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?, channel: PageChannel?, editing: Boolean, origin: ArtifactOrigin?) {
    val current by rememberUpdatedState(onMessage)
    val serving = remember { Serving() }
    serving.origin = origin
    val loaded = remember { arrayOf<String?>(null) }
    AndroidView(
    factory = { context ->
        AndroidWebView(context).apply {
            // Without a size of its own the page's viewport height starts at zero, and a frame sized in vh stays that tall.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // Every frame sees it, so a page's own script can call it; what it may send is checked where the messages are handled.
            if (onMessage != null) addJavascriptInterface(EditBridge { current }, "Kinuvo")
            settings.javaScriptEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            if (origin != null) {
                settings.domStorageEnabled = true
                webViewClient = OriginClient(serving)
                forget(origin.base)
            }
        }
    },
    modifier = modifier,
    update = { channel?.send = { message -> it.evaluateJavascript(pageDelivery(message), null) }
        if (loaded[0] != html) { loaded[0] = html; it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) } },
    onRelease = { channel?.send = {}; serving.origin?.let { o -> forget(o.base) }; serving.origin = null; it.destroy() },
    )
}
