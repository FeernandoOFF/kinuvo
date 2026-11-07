package kinuvo.ui.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.Artboard
import kinuvo.ui.design.*

/** What the canvas page tells the app, decoded from the JSON the bridge passes on. */
internal sealed interface DesignMessage {
    data class Open(val id: String) : DesignMessage
    data class Edit(val json: String) : DesignMessage
}

internal fun designMessage(text: String): DesignMessage? = runCatching {
    val m = Json.parseToJsonElement(text).jsonObject
    when (m["kinuvo"]?.jsonPrimitive?.content) {
        "design-open" -> DesignMessage.Open(m.getValue("id").jsonPrimitive.content)
        "design-edit" -> DesignMessage.Edit(m.getValue("json").jsonPrimitive.content)
        else -> null
    }
}.getOrNull()

/** The [json] design's artboards on a pan and zoom canvas: a tap on an artboard's expand button reports [onOpen], and canvas edits come back as the whole design through [onEdit]. */
@Composable
internal fun DesignCanvas(json: String, background: Color, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null, onEdit: ((String) -> Unit)? = null) {
    val open by rememberUpdatedState(onOpen)
    val edit by rememberUpdatedState(onEdit)
    WebView(
        remember(json, background) { designDocument(json, background = background) },
        modifier,
        if (onOpen == null && onEdit == null) null else { text ->
            when (val m = designMessage(text)) {
                is DesignMessage.Open -> open?.invoke(m.id)
                is DesignMessage.Edit -> edit?.invoke(m.json)
                null -> Unit
            }
        },
    )
}

private fun Color.css() = "rgb(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()})"

/** One trusted page: the canvas script is the only script allowed, and the artboards are script-less same-origin frames it edits. */
internal fun designDocument(json: String, nonce: String = newNonce(), background: Color = Color(0xFF1E1E1E)): String {
    val inner = sandboxed(
        "<body><div id=\"s\"><div id=\"w\"></div></div><div id=\"o\"></div><style>${designCss(background.css(), background.luminance() <= 0.5f)}</style><script nonce=\"$nonce\">${designScript(json)}</script></body>",
        "'nonce-$nonce'",
    )
    return framed(inner, "<script>$BRIDGE</script>" + topFade(background), "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no", "allow-scripts allow-same-origin")
}

private val Artboard.label get() = name.ifBlank { id }

/** A single artboard at its real size, shrunk to the page's width when wider. */
internal fun artboardDocument(board: Artboard, background: Color): String =
    "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
        "<body style=\"margin:0;background:${background.css()}\"><div id=\"o\" style=\"overflow:hidden\"><div id=\"c\" style=\"transform-origin:0 0;width:${board.width}px;height:${board.height}px\">" +
        "<iframe sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" style=\"border:0;width:100%;height:100%;display:block\" srcdoc=\"${attr(sandboxed(board.html))}\"></iframe></div></div>" +
        "<script>var c=document.getElementById('c'),o=document.getElementById('o');function r(){var s=Math.min(1,innerWidth/${board.width});c.style.transform='scale('+s+')';o.style.width=${board.width}*s+'px';o.style.height=${board.height}*s+'px'}addEventListener('resize',r);r()</script></body></html>"

/** One artboard of [boards] full screen; [present] adds previous and next. */
@Composable
internal fun ArtboardViewer(boards: List<Artboard>, index: Int, present: Boolean, background: Color, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val board = boards.getOrNull(index) ?: return
    val density = LocalDensity.current
    var controls by remember { mutableStateOf(ControlsHeight) }
    Box(Modifier.fillMaxSize().background(background)) {
        WebView(remember(board, background) { artboardDocument(board, background) }, Modifier.fillMaxSize().padding(top = controls))
        val chrome = LocalWindowChrome.current
        Row(
            Modifier.fillMaxWidth().onSizeChanged { controls = with(density) { it.height.toDp() } }
                .padding(chrome?.padding(true, true) ?: PaddingValues()).padding(horizontal = Spacing.space10, vertical = Spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
        ) {
            FloatingBar { FloatingButton(Lucide.X, if (present) "Exit" else "Close", onClose) }
            FloatingBar {
                Text(board.label, Modifier.padding(horizontal = Spacing.space10).widthIn(max = 220.dp), fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            if (present) FloatingBar {
                FloatingButton(Lucide.ChevronLeft, "Previous") { if (index > 0) onIndex(index - 1) }
                Text("${index + 1} / ${boards.size}", Modifier.padding(horizontal = Spacing.space4), style = TextStyles.secondary, color = color(mutedColor))
                FloatingButton(Lucide.ChevronRight, "Next") { if (index < boards.lastIndex) onIndex(index + 1) }
            }
        }
    }
}
