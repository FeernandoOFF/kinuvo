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

/** The [json] design's artboards on a pan and zoom canvas, or only the artboard [focus] filling the page ([fill]) or fitted: a tap on an artboard's expand button reports [onOpen], and canvas edits come back as the whole design through [onEdit]. */
@Composable
internal fun DesignCanvas(json: String, background: Color, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null, onEdit: ((String) -> Unit)? = null, focus: String? = null, fill: Boolean = false) {
    val open by rememberUpdatedState(onOpen)
    val edit by rememberUpdatedState(onEdit)
    WebView(
        remember(json, background, focus, fill) { designDocument(json, background = background, focus = focus, fill = fill) },
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
internal fun designDocument(json: String, nonce: String = newNonce(), background: Color = Color(0xFF1E1E1E), focus: String? = null, fill: Boolean = false): String {
    val inner = sandboxed(
        "<body><div id=\"s\"><div id=\"w\"></div></div><div id=\"o\"></div><style>${designCss(background.css(), background.luminance() <= 0.5f)}</style><script nonce=\"$nonce\">${designScript(json, focus, fill)}</script></body>",
        "'nonce-$nonce'",
    )
    return framed(inner, "<script>$BRIDGE</script>" + topFade(background), "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no", "allow-scripts allow-same-origin")
}

private val Artboard.label get() = name.ifBlank { id }

/** A single artboard at its real size, shrunk to the page's width when wider. */
internal fun artboardDocument(board: Artboard, background: Color): String =
    "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>" +
        "body{margin:0;height:100vh;display:flex;overflow:auto;background:${background.css()}}#o{margin:auto;flex:none;overflow:hidden}#c{transform-origin:0 0;width:${board.width}px;height:${board.height}px}" +
        "#z{position:fixed;right:12px;bottom:12px;display:flex;gap:2px;padding:3px;border-radius:999px;background:rgba(40,40,40,.85);font:12px system-ui,sans-serif}" +
        "#z button{all:unset;cursor:pointer;min-width:28px;height:28px;padding:0 6px;text-align:center;border-radius:999px;color:#eee}#z button:hover{background:rgba(255,255,255,.12)}</style></head>" +
        "<body><div id=\"o\"><div id=\"c\"><iframe sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" style=\"border:0;width:100%;height:100%;display:block\" srcdoc=\"${attr(sandboxed(board.html))}\"></iframe></div></div>" +
        "<div id=\"z\"><button id=\"m\" title=\"Zoom out\">−</button><button id=\"p\" title=\"Fit\"></button><button id=\"a\" title=\"Zoom in\">+</button></div>" +
        "<script>var W=${board.width},H=${board.height},z=1,c=document.getElementById('c'),o=document.getElementById('o'),p=document.getElementById('p');" +
        "function r(){var s=Math.min(innerWidth/W,innerHeight/H,1)*z;c.style.transform='scale('+s+')';o.style.width=W*s+'px';o.style.height=H*s+'px';p.textContent=Math.round(s*100)+'%'}" +
        "function zz(f){z=f?Math.min(8,Math.max(.1,z*f)):1;r()}document.getElementById('m').onclick=function(){zz(.8)};document.getElementById('a').onclick=function(){zz(1.25)};p.onclick=function(){zz(0)};" +
        "addEventListener('keydown',function(e){var f=e.key==='+'||e.key==='='?1.25:e.key==='-'||e.key==='_'?.8:e.key==='0'?0:null;if(f!==null){zz(f);e.preventDefault()}});" +
        "addEventListener('wheel',function(e){if(e.ctrlKey||e.metaKey){e.preventDefault();zz(Math.exp(-e.deltaY*.01))}},{passive:false});addEventListener('resize',r);r()</script></body></html>"

/** The artboard [id] alone on the same canvas, editable; its edits save like the canvas's own and don't reload it. */
@Composable
internal fun DesignFocus(content: String, id: String, name: String, background: Color, onEdit: (String) -> Unit, onClose: () -> Unit) {
    val density = LocalDensity.current
    var controls by remember { mutableStateOf(ControlsHeight) }
    var fill by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(background)) {
        key(fill) {
            val sent = remember { mutableSetOf<String>() }
            var shown by remember { mutableStateOf(content) }
            LaunchedEffect(content) { if (content !in sent) shown = content }
            DesignCanvas(shown, background, Modifier.fillMaxSize().padding(top = controls), onEdit = { json -> sent += json; if (sent.size > 16) sent.remove(sent.first()); onEdit(json) }, focus = id, fill = fill)
        }
        val chrome = LocalWindowChrome.current
        Row(
            Modifier.fillMaxWidth().onSizeChanged { controls = with(density) { it.height.toDp() } }
                .padding(chrome?.padding(true, true) ?: PaddingValues()).padding(horizontal = Spacing.space10, vertical = Spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
        ) {
            FloatingBar { FloatingButton(Lucide.ArrowLeft, "Back", onClose) }
            FloatingBar { Text(name, Modifier.padding(horizontal = Spacing.space10).widthIn(max = 200.dp), fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.weight(1f))
            FloatingBar { Segmented(listOf("Fill", "Fit"), if (fill) 0 else 1, Modifier, small = true) { fill = it == 0 } }
        }
    }
}

/** The artboards one at a time, full screen with their scripts running, with previous and next. */
@Composable
internal fun ArtboardViewer(boards: List<Artboard>, index: Int, background: Color, onIndex: (Int) -> Unit, onClose: () -> Unit) {
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
            FloatingBar { FloatingButton(Lucide.X, "Exit", onClose) }
            FloatingBar {
                Text(board.label, Modifier.padding(horizontal = Spacing.space10).widthIn(max = 220.dp), fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            FloatingBar {
                FloatingButton(Lucide.ChevronLeft, "Previous") { if (index > 0) onIndex(index - 1) }
                Text("${index + 1} / ${boards.size}", Modifier.padding(horizontal = Spacing.space4), style = TextStyles.secondary, color = color(mutedColor))
                FloatingButton(Lucide.ChevronRight, "Next") { if (index < boards.lastIndex) onIndex(index + 1) }
            }
        }
    }
}
