package kinuvo.ui.artifacts

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

internal actual val visualEditing = true

private class EditBridge(val onMessage: () -> ((String) -> Unit)?) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun edit(html: String) { main.post { onMessage()?.invoke(html) } }
}

@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?) {
    val current by rememberUpdatedState(onMessage)
    AndroidView(
    factory = { context ->
        android.webkit.WebView(context).apply {
            // Every frame sees it; only the visual editor sets onMessage, and its policy runs no artifact scripts.
            if (onMessage != null) addJavascriptInterface(EditBridge { current }, "Kinuvo")
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
        }
    },
    modifier = modifier,
    update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
    onRelease = { it.destroy() },
    )
}
