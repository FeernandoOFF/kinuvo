package kinuvo.ui.surface

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup.LayoutParams
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    val current = rememberUpdatedState(bridge)
    AndroidView(factory = { context -> WebView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "kinuvoNative", setOf("https://html.kinuvo.invalid")) { _, message, _, mainFrame, _ ->
                if (mainFrame) message.data?.let { current.value.receive(it) }
            }
        }
        current.value.post = { evaluateJavascript(htmlSurfaceDelivery(it), null) }
        loadDataWithBaseURL("https://html.kinuvo.invalid/", document, "text/html", "utf-8", null)
    } }, modifier = modifier, onRelease = {
        current.value.post = {}
        it.stopLoading()
        it.destroy()
    })
}

internal actual val htmlSurfacePlatform = "mobile"
internal actual fun htmlSurfaceLocale(): String = Locale.getDefault().toLanguageTag()
