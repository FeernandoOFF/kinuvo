package kinuvo.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.nucleusframework.webview.jsbridge.IJsMessageHandler
import dev.nucleusframework.webview.jsbridge.JsMessage
import dev.nucleusframework.webview.jsbridge.WebViewJsBridge
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import kotlin.io.encoding.Base64
import kinuvo.ui.design.LocalWebEscape
import kinuvo.ui.design.LocalWebPointer
import kinuvo.ui.design.LocalWebWheel

private fun handler(name: String, run: (String) -> Unit) = object : IJsMessageHandler {
    override fun methodName() = name
    override fun handle(message: JsMessage, navigator: WebViewNavigator?, callback: (String) -> Unit) = run(message.params)
}

/** Esc the page didn't handle, pointer moves near a full-screen page's top, and wheel deltas a framed page couldn't use, go to the app. */
private const val RELAY = "(function(){function n(m,p){if(window.kmpJsBridge)window.kmpJsBridge.callNative(m,p||'')}" +
    "addEventListener('keydown',function(e){if(e.key==='Escape'&&!e.defaultPrevented)n('escape')});" +
    "addEventListener('message',function(e){var d=e.data;if(d==='kinuvo:escape')n('escape');else if(d==='kinuvo:top'||d==='kinuvo:away')n('pointer',d.slice(7));else if(d&&typeof d.kinuvoWheel==='number')n('wheel',String(d.kinuvoWheel))})})();"

@Composable
internal fun NativeHtml(document: String, script: String, modifier: Modifier, receive: (String) -> Unit, connect: @Composable (WebViewNavigator) -> Unit) {
    val current = rememberUpdatedState(receive)
    val navigator = rememberWebViewNavigator()
    val url = remember(document) { "data:text/html;charset=utf-8;base64," + Base64.encode(document.encodeToByteArray()) }
    val state = rememberWebViewState(url)
    val bridge = remember(navigator) {
        WebViewJsBridge(navigator).apply {
            register(object : IJsMessageHandler {
                override fun methodName() = "message"
                override fun handle(message: JsMessage, navigator: WebViewNavigator?, callback: (String) -> Unit) {
                    current.value(message.params)
                }
            })
        }
    }
    val escape = rememberUpdatedState(LocalWebEscape.current)
    val wheel = rememberUpdatedState(LocalWebWheel.current)
    val pointer = rememberUpdatedState(LocalWebPointer.current)
    remember(bridge) {
        bridge.register(handler("escape") { escape.value?.invoke() })
        bridge.register(handler("pointer") { pointer.value?.invoke(it == "top") })
        bridge.register(handler("wheel") { wheel.value?.invoke(it.toFloatOrNull() ?: 0f) })
    }
    state.webSettings.desktopWebSettings.apply {
        transparent = true
        incognito = true
        initScript = RELAY + script
    }
    connect(navigator)
    WebView(state, modifier, navigator, bridge)
}
