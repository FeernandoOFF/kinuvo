package kinuvo.ui.surface

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.composables.ui.components.Text
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent
import java.util.Locale

@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    if (currentPlatform != Platform.MacOS) {
        Text("HTML Surface — desktop host pending", modifier)
        return
    }
    val view = remember(bridge) { runCatching { MacHtmlView() } }
    val host = view.getOrNull()
    if (host == null) {
        Text("HTML Surface — ${view.exceptionOrNull()?.message ?: "host unavailable"}", modifier)
        return
    }
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    fun send(op: String, body: JsonObjectBuilder.() -> Unit = {}) = host.send(buildJsonObject { put("op", op); body() })
    DisposableEffect(host, bridge) {
        bridge.post = { message -> send("eval") { put("js", htmlSurfaceDelivery(message)) } }
        onDispose { bridge.post = {}; host.close() }
    }
    LaunchedEffect(host, document) { send("load") { put("html", document) } }
    LaunchedEffect(host, bridge) {
        while (true) {
            for (i in 0 until 64) bridge.receive(host.poll() ?: break)
            host.pixels()?.let { image = it.image() }
            delay(16)
        }
    }
    val input = modifier.onSizeChanged { size ->
        send("size") { put("width", size.width / density); put("height", size.height / density) }
    }.focusRequester(focus).onFocusChanged { send("focus") { put("focused", it.isFocused) } }.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return@onKeyEvent false
        val native = event.awtEventOrNull ?: return@onKeyEvent false
        val text = (if (event.isMetaPressed && native.keyCode in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z) native.keyCode.toChar().lowercaseChar() else native.keyChar).takeUnless { it == AwtKeyEvent.CHAR_UNDEFINED }?.toString().orEmpty()
        val code = when (native.keyCode) {
            AwtKeyEvent.VK_BACK_SPACE -> 51; AwtKeyEvent.VK_DELETE -> 117; AwtKeyEvent.VK_ENTER -> 36
            AwtKeyEvent.VK_TAB -> 48; AwtKeyEvent.VK_ESCAPE -> 53; AwtKeyEvent.VK_LEFT -> 123
            AwtKeyEvent.VK_RIGHT -> 124; AwtKeyEvent.VK_DOWN -> 125; AwtKeyEvent.VK_UP -> 126
            else -> 0
        }
        val modifiers = (if (event.isShiftPressed) 1 shl 17 else 0) or (if (event.isCtrlPressed) 1 shl 18 else 0) or
            (if (event.isAltPressed) 1 shl 19 else 0) or (if (event.isMetaPressed) 1 shl 20 else 0)
        // Typed characters go in as text, which needs no key window; key-ups only fire the page's keyup handlers.
        if (event.type == KeyEventType.KeyUp) send("key") { put("text", text); put("code", code); put("modifiers", modifiers); put("up", true) }
        else if (code == 0 && !event.isMetaPressed && !event.isCtrlPressed && text.isNotEmpty()) send("text") { put("text", text) }
        else send("key") { put("text", text); put("code", code); put("modifiers", modifiers) }
        true
    }.focusable().pointerInput(host, density, document) {
        var lastPress = 0L
        var clicks = 0
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (event.type == PointerEventType.Press) {
                    focus.requestFocus()
                    clicks = if (change.uptimeMillis - lastPress < 400) clicks + 1 else 1
                    lastPress = change.uptimeMillis
                }
                if (event.type == PointerEventType.Scroll) {
                    if (document.contains("overflow:auto")) {
                        send("scroll") { put("dx", -change.scrollDelta.x * 30); put("dy", -change.scrollDelta.y * 30) }
                        change.consume()
                    }
                    continue
                }
                val kind = when (event.type) {
                    PointerEventType.Press -> "down"
                    PointerEventType.Release -> "up"
                    PointerEventType.Move -> if (change.pressed) "drag" else "move"
                    else -> continue
                }
                val secondary = event.awtEventOrNull?.let { it.button == MouseEvent.BUTTON3 || it.modifiersEx and MouseEvent.BUTTON3_DOWN_MASK != 0 } == true
                send("mouse") { put("kind", kind); put("secondary", secondary); put("clicks", clicks); put("modifiers", if (event.keyboardModifiers.isShiftPressed) 1 shl 17 else 0); put("x", change.position.x / density); put("y", change.position.y / density) }
                change.consume()
            }
        }
    }
    val bitmap = image
    if (bitmap != null) Image(bitmap, "HTML Surface", input, contentScale = ContentScale.FillBounds)
    else Text("Loading HTML Surface…", input)
}

internal actual val htmlSurfacePlatform = "desktop"
internal actual fun htmlSurfaceLocale(): String = Locale.getDefault().toLanguageTag()
