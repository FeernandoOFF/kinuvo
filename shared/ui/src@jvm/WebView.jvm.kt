package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import ca.weblite.webview.swing.WebViewComponent
import kotlin.io.encoding.Base64

/** The OS web view (WKWebView, WebView2, WebKitGTK) through swingwebview; it draws above Compose content. */
@Composable
internal actual fun WebView(html: String, modifier: Modifier) {
    val url = remember(html) { "data:text/html;charset=utf-8;base64," + Base64.encode(html.encodeToByteArray()) }
    SwingPanel(
        factory = { WebViewComponent.create() },
        modifier = modifier,
        update = { if (it.url != url) it.setUrl(url) },
    )
}
