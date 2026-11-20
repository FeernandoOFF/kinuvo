package kinuvo.ui.artifacts

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import kinuvo.ui.web.NativeHtml

internal actual val visualEditing = true

@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?, channel: PageChannel?) {
    if (!LocalNativeViews.current) return Box(modifier)
    NativeHtml(html, "window.kinuvoEdit=m=>window.kmpJsBridge.callNative('message',m);", modifier, { onMessage?.invoke(it) }) { navigator ->
        DisposableEffect(channel, navigator) {
            channel?.send = { navigator.evaluateJavaScript(pageDelivery(it)) }
            onDispose { channel?.send = {} }
        }
    }
}
