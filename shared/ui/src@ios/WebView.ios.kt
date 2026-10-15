package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun WebView(html: String, modifier: Modifier) = UIKitView(
    factory = { WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() }) },
    modifier = modifier,
    update = { it.loadHTMLString(html, baseURL = null) },
)
