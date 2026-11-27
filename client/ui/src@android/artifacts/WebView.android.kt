package kinuvo.ui.artifacts

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView as AndroidWebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.ui.design.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile

internal actual val visualEditing = true

internal actual val popupsSupported = true

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

private fun AndroidWebView.browse(uri: android.net.Uri) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Serves the origin's site and keeps the page there: anything else is blocked, and a link the user taps opens in the browser.
 * A popup window ([closeWindow]) also sends what it is asked to load elsewhere to the browser and closes.
 */
private class OriginClient(val serving: Serving, val closeWindow: (() -> Unit)? = null) : WebViewClient() {
    override fun shouldInterceptRequest(view: AndroidWebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val origin = serving.origin?.takeIf { it.owns(url) }
        if (origin == null) return if (request.isForMainFrame && request.url.scheme in setOf("http", "https")) WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf())) else null
        val r = if (request.method == "GET") runBlocking { origin.serve(url) } else null
        return if (r == null) WebResourceResponse("text/plain", "utf-8", 405, "Method Not Allowed", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        else WebResourceResponse(r.mime, if ("charset" in r.contentType) "utf-8" else null, r.status, REASONS[r.status] ?: "Error", r.headers, ByteArrayInputStream(r.body))
    }

    override fun shouldOverrideUrlLoading(view: AndroidWebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (serving.origin?.owns(url) == true) return false
        if (closeWindow != null) {
            if (popupAction(url, serving.origin) == PopupAction.Browser) { view.browse(request.url); closeWindow() }
        } else if (request.hasGesture() && request.url.scheme in setOf("http", "https", "mailto")) view.browse(request.url)
        return true
    }
}

/** Opens the windows a granted page asks for as [show]n dialogs, each a web view of its own that keeps the opener, so a page can fill it. */
private class PopupChrome(val serving: Serving, val show: (AndroidWebView?) -> Unit) : WebChromeClient() {
    override fun onCreateWindow(view: AndroidWebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val window = AndroidWebView(view.context)
        window.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        secure(window.settings)
        window.settings.domStorageEnabled = true
        window.webViewClient = OriginClient(serving) { close(window) }
        window.webChromeClient = this
        (resultMsg.obj as AndroidWebView.WebViewTransport).webView = window
        resultMsg.sendToTarget()
        show(window)
        return true
    }

    override fun onCloseWindow(window: AndroidWebView) = close(window)

    private fun close(window: AndroidWebView) {
        show(null)
        Handler(Looper.getMainLooper()).post { window.destroy() }
    }
}

private fun secure(settings: android.webkit.WebSettings) {
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
}

@Composable
private fun PopupDialog(window: AndroidWebView, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(color(backgroundColor)).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.space8, vertical = Spacing.space4), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Icon(Lucide.X, contentDescription = "Close window") }
                Text("Artifact window", Modifier.padding(start = Spacing.space4), color = color(mutedColor), singleLine = true)
            }
            HorizontalSeparator()
            AndroidView({ window }, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Web storage and cookies of an Artifact's origin end with its view: its state is the Server's. */
private fun forget(base: String) {
    WebStorage.getInstance().deleteOrigin(base)
    val cookies = CookieManager.getInstance()
    cookies.getCookie(base)?.split(";")?.forEach { cookies.setCookie(base, "${it.substringBefore('=').trim()}=; Max-Age=0") }
}

@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?, channel: PageChannel?, editing: Boolean, origin: ArtifactOrigin?, popups: Boolean) {
    val current by rememberUpdatedState(onMessage)
    val serving = remember { Serving() }
    serving.origin = origin
    val loaded = remember { arrayOf<String?>(null) }
    var window by remember { mutableStateOf<AndroidWebView?>(null) }
    val chrome = remember { PopupChrome(serving) { window = it } }
    window?.let { shown -> PopupDialog(shown) { window = null; shown.destroy() } }
    DisposableEffect(Unit) { onDispose { window?.destroy(); window = null } }
    AndroidView(
    factory = { context ->
        AndroidWebView(context).apply {
            // Without a size of its own the page's viewport height starts at zero, and a frame sized in vh stays that tall.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // Every frame sees it, so a page's own script can call it; what it may send is checked where the messages are handled.
            if (onMessage != null) addJavascriptInterface(EditBridge { current }, "Kinuvo")
            secure(settings)
            if (origin != null) {
                settings.domStorageEnabled = true
                webViewClient = OriginClient(serving)
                forget(origin.base)
            }
        }
    },
    modifier = modifier,
    update = { channel?.send = { message -> it.evaluateJavascript(pageDelivery(message), null) }
        it.settings.setSupportMultipleWindows(popups)
        it.webChromeClient = if (popups) chrome else null
        if (loaded[0] != html) { loaded[0] = html; it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) } },
    onRelease = { channel?.send = {}; serving.origin?.let { o -> forget(o.base) }; serving.origin = null; it.destroy() },
    )
}
