package kinuvo.ui.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import kinuvo.ui.artifacts.LocalNativeViews
import kinuvo.ui.web.NativeHtml
import java.util.Locale

@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    if (!LocalNativeViews.current) return Box(modifier)
    NativeHtml(document, "window.kinuvoNative={postMessage:m=>window.kmpJsBridge.callNative('message',m)};", modifier, bridge::receive) { navigator ->
        DisposableEffect(bridge, navigator) {
            bridge.post = { navigator.evaluateJavaScript(htmlSurfaceDelivery(it)) }
            onDispose { bridge.post = {} }
        }
    }
}

internal actual val htmlSurfacePlatform = "desktop"
internal actual fun htmlSurfaceLocale(): String = Locale.getDefault().toLanguageTag()
