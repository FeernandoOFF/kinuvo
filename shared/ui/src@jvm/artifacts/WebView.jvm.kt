package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import ca.weblite.webview.swing.WebViewComponent
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal actual val visualEditing = true

/** The callback gets `{"name","seq","args":[…]}`, with what the page passed in `args`. */
internal fun editedHtml(args: String): String =
    runCatching { Json.parseToJsonElement(args).jsonObject.getValue("args").jsonArray.first().jsonPrimitive.content }.getOrDefault(args)

/** The OS web view (WKWebView, WebView2, WebKitGTK) through swingwebview; it draws above Compose content. */
@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?) {
    val current by rememberUpdatedState(onMessage)
    val url = remember(html) { "data:text/html;charset=utf-8;base64," + Base64.encode(html.encodeToByteArray()) }
    SwingPanel(
        factory = {
            WebViewComponent.create().apply {
                if (onMessage != null) addJavascriptCallback("kinuvoEdit") { args -> current?.invoke(editedHtml(args)) }
            }
        },
        modifier = modifier,
        update = { if (it.url != url) it.setUrl(url) },
    )
}
