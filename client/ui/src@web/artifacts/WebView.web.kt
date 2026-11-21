package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

internal actual val visualEditing = false

/** The page in a sandboxed iframe over the canvas; Compose can't take input above it, so it hides while a menu covers it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun WebView(html: String, modifier: Modifier, onMessage: ((String) -> Unit)?, channel: PageChannel?) {
    val covered = LocalWebViewCovered.current
    val frame = remember { (document.createElement("iframe") as HTMLIFrameElement).apply {
        setAttribute("sandbox", "allow-scripts")
        setAttribute("referrerpolicy", "no-referrer")
        style.border = "0"
        style.background = "transparent"
    } }
    val page = remember(html) { html + RELAY }
    SideEffect {
        if (frame.srcdoc != page) frame.srcdoc = page
        frame.style.visibility = if (covered) "hidden" else "visible"
    }
    DisposableEffect(frame, channel) {
        channel?.send = { postMessage(frame, it) }
        onDispose { channel?.send = {} }
    }
    WebElementView(factory = { frame }, modifier = modifier)
}

/** Messages from the app arrive as strings and go where [pageDelivery] would send them on other platforms. */
private const val RELAY = "<script>addEventListener('message',function(e){if(e.source===parent&&typeof e.data==='string'&&window.kinuvoReceive)window.kinuvoReceive(e.data)})</script>"

private fun postMessage(frame: HTMLIFrameElement, message: String): Unit = js("frame.contentWindow&&frame.contentWindow.postMessage(message, '*')")
