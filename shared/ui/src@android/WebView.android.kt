package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal actual fun WebView(html: String, modifier: Modifier) = AndroidView(
    factory = { context ->
        android.webkit.WebView(context).apply {
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
