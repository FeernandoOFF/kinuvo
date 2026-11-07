package kinuvo.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document as browserDocument
import kotlinx.browser.window
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    val frame = remember { (browserDocument.createElement("iframe") as HTMLIFrameElement).apply {
        setAttribute("sandbox", "allow-scripts")
        setAttribute("referrerpolicy", "no-referrer")
        style.border = "0"
        style.background = "transparent"
        setAttribute("allowtransparency", "true")
        srcdoc = document
    } }
    DisposableEffect(frame, bridge) {
        val listener: (Event) -> Unit = { event ->
            val message = event as MessageEvent
            if (message.source == frame.contentWindow) messageText(message)?.let(bridge::receive)
        }
        window.addEventListener("message", listener)
        bridge.post = { postMessage(frame, it) }
        onDispose { bridge.post = {}; window.removeEventListener("message", listener); frame.remove() }
    }
    WebElementView(factory = { frame }, modifier = modifier)
}

internal actual val htmlSurfacePlatform = "web"
internal actual fun htmlSurfaceLocale(): String = window.navigator.language

private fun postMessage(frame: HTMLIFrameElement, message: String): Unit = js("frame.contentWindow.postMessage(message, '*')")
private fun messageText(event: MessageEvent): String? = js("typeof event.data === 'string' ? event.data : null")
