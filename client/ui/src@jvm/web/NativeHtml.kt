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
import dev.nucleusframework.webview.web.rememberWebViewStateWithHTMLData

@Composable
internal fun NativeHtml(document: String, script: String, modifier: Modifier, receive: (String) -> Unit, connect: @Composable (WebViewNavigator) -> Unit) {
    val current = rememberUpdatedState(receive)
    val navigator = rememberWebViewNavigator()
    val state = rememberWebViewStateWithHTMLData(document)
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
    state.webSettings.desktopWebSettings.apply {
        transparent = true
        incognito = true
        initScript = script
    }
    connect(navigator)
    WebView(state, modifier, navigator, bridge)
}
