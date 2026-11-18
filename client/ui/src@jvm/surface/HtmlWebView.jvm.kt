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
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent
import java.util.Locale

@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    val view = remember(bridge) { runCatching {
        if (currentPlatform == Platform.MacOS) MacHtmlView() else ChromiumHtmlView()
    } }
    val host = view.getOrNull()
    if (host == null) {
        Text(view.exceptionOrNull()?.message ?: "HTML Surface host unavailable", modifier)
        return
    }
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    fun send(op: String, body: JsonObjectBuilder.() -> Unit = {}) = host.send(buildJsonObject { put("op", op); body() })
    DisposableEffect(host, bridge) {
        bridge.post = { message -> send("eval") { put("js", htmlSurfaceDelivery(message)) } }
        onDispose { bridge.post = {}; host.close() }
    }
    LaunchedEffect(host, document) { send("load") { put("html", document) } }
    LaunchedEffect(host, bridge) {
        while (true) {
            try {
                for (i in 0 until 64) bridge.receive(host.poll() ?: break)
                host.image()?.let { image = it }
            } catch (e: Exception) {
                failure = "HTML Surface — ${e.cause?.message ?: e.message}"
                return@LaunchedEffect
            }
            delay(16)
        }
    }
    val input = modifier.onSizeChanged { size ->
        send("size") { put("width", size.width / density); put("height", size.height / density); put("density", density) }
    }.focusRequester(focus).onFocusChanged { send("focus") { put("focused", it.isFocused) } }.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return@onKeyEvent false
        val native = event.awtEventOrNull ?: return@onKeyEvent false
        val text = (if (event.isMetaPressed && native.keyCode in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z) native.keyCode.toChar().lowercaseChar() else native.keyChar).takeUnless { it == AwtKeyEvent.CHAR_UNDEFINED }?.toString().orEmpty()
        send("key") {
            put("text", text); put("code", native.keyCode); put("modifiers", native.modifiersEx)
            put("up", event.type == KeyEventType.KeyUp)
        }
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
                        send("scroll") { put("dx", -change.scrollDelta.x * 30); put("dy", -change.scrollDelta.y * 30); put("x", change.position.x / density); put("y", change.position.y / density); put("modifiers", event.awtEventOrNull?.modifiersEx ?: 0) }
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
                send("mouse") { put("kind", kind); put("secondary", secondary); put("clicks", clicks); put("modifiers", event.awtEventOrNull?.modifiersEx ?: 0); put("x", change.position.x / density); put("y", change.position.y / density) }
                change.consume()
            }
        }
    }
    val bitmap = image
    if (failure != null) Text(failure!!, modifier)
    else if (bitmap != null) Image(bitmap, "HTML Surface", input, contentScale = ContentScale.FillBounds)
    else Text("Loading HTML Surface…", input)
}

internal actual val htmlSurfacePlatform = "desktop"
internal actual fun htmlSurfaceLocale(): String = Locale.getDefault().toLanguageTag()
