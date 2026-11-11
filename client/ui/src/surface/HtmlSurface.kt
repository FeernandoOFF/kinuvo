package kinuvo.ui.surface

import kinuvo.protocol.session.ToolCallId
import arrow.core.Either
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.ui.design.*
import kinuvo.app.session.ChatItem
import kinuvo.app.surface.AppHost
import kinuvo.protocol.mcp.McpAppResource
import kotlinx.serialization.json.*

internal class HtmlBridge(val receive: (String) -> List<String>) {
    var post: (String) -> Unit = {}
    fun receive(raw: String) { receive.invoke(raw).forEach { post(it) } }
}

internal val LocalHtmlSurfaces = staticCompositionLocalOf { mutableStateListOf<ToolCallId>() }

internal val LocalHtmlHeights = staticCompositionLocalOf<MutableMap<ToolCallId, Int>> { mutableMapOf() }

@Composable
internal expect fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier)

@Composable
internal fun HtmlSurface(call: ChatItem.ToolCall, send: (String) -> Unit, fallback: @Composable () -> Unit) {
    val args = remember(call.args) { runCatching { Json.parseToJsonElement(call.args).jsonObject }.getOrNull() } ?: run { fallback(); return }
    val html = (args["html"] as? JsonPrimitive)?.content ?: run { fallback(); return }
    val result = remember(call.result) {
        buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", call.result.orEmpty()) }) }) }
    }
    HtmlSurface(call.callId, html, (args["title"] as? JsonPrimitive)?.content ?: "Interactive page", "HTML Surface", Lucide.AppWindow,
        args["data"] as? JsonObject ?: JsonObject(emptyMap()), result, HtmlPolicy(), null, send)
}

/** An MCP App's UI for [call], fetched from the Server; its own tool calls go through [callTool]. */
@Composable
internal fun McpAppSurface(
    call: ChatItem.ToolCall,
    resource: suspend (ToolCallId) -> McpAppResource,
    callTool: suspend (ToolCallId, String, JsonObject) -> JsonObject,
    send: (String) -> Unit,
    fallback: @Composable () -> Unit,
) {
    val loaded by produceState<McpAppResource?>(null, call.callId) { value = Either.catch { resource(call.callId) }.getOrNull() }
    val app = loaded ?: run { fallback(); return }
    val input = remember(call.args) { runCatching { Json.parseToJsonElement(call.args).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
    val result = call.appResult ?: buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", call.result.orEmpty()) }) })
    }
    val latest by rememberUpdatedState(callTool)
    HtmlSurface(call.callId, app.html, call.identity?.localId ?: call.name, "MCP App · ${call.app?.serverId?.substringAfterLast('/')}", Lucide.Blocks, input, result,
        HtmlPolicy.of(app.ui), { name, args -> latest(call.callId, name, args) }, send)
}

@Composable
private fun HtmlSurface(
    id: ToolCallId,
    html: String,
    title: String,
    caption: String,
    icon: ImageVector,
    input: JsonObject,
    toolResult: JsonObject,
    policy: HtmlPolicy,
    callTool: (suspend (String, JsonObject) -> JsonObject)?,
    send: (String) -> Unit,
) {
    val heights = LocalHtmlHeights.current
    val surfaces = LocalHtmlSurfaces.current
    var visible by remember { mutableStateOf(false) }
    val showing = visible
    DisposableEffect(id, showing) {
        if (showing) surfaces.add(id)
        onDispose { if (showing) surfaces.remove(id) }
    }
    val live = visible && surfaces.indexOf(id) in 0 until MAX_LIVE
    val opener = LocalUriHandler.current
    val theme = htmlTheme()
    val latestSend by rememberUpdatedState(send)
    val scope = rememberCoroutineScope()
    val host = remember(id, input, toolResult, theme) {
        AppHost(input, buildJsonObject {
            put("theme", if (theme.dark) "dark" else "light")
            put("styles", buildJsonObject { put("variables", JsonObject(theme.variables.mapValues { JsonPrimitive(it.value) })) })
            put("platform", htmlSurfacePlatform)
            put("locale", htmlSurfaceLocale())
            put("containerDimensions", buildJsonObject { put("maxHeight", 560) })
        }, { latestSend(it) }, { opener.openUri(it) }, callTool, scope, initialHeight = heights[id] ?: 240, toolResult = toolResult)
    }
    val state by host.state.collectAsState()
    SideEffect { heights[id] = state.height }
    val expanded = state.displayMode == "fullscreen"
    val shape = Theme[shapes][largeShape]
    DisableSelection {
        // Like any other reply, the page sits in the chat itself; only a small expand control floats over it.
        Box(Modifier.fillMaxWidth().height(state.height.dp).semantics { contentDescription = "$title, $caption" }
            .onGloballyPositioned { visible = it.boundsInWindow().let { b -> b.width > 0 && b.height > 0 } }) {
            if (live && !expanded) HtmlSurfaceView(html, policy, theme, host, false, Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize().clip(shape).background(color(subtleColor)).clickable(enabled = !expanded) { surfaces.remove(id); surfaces.add(0, id) },
                verticalArrangement = Arrangement.spacedBy(Spacing.space6, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, Modifier.size(20.dp), tint = color(mutedColor))
                Text(if (expanded) title else "Tap to load $title", style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
            }
            if (!expanded) Box(Modifier.align(Alignment.TopEnd).padding(Spacing.space6).size(28.dp).clip(CircleShape)
                .background(color(panelColor).copy(alpha = 0.72f)).border(1.dp, color(borderColor).copy(alpha = 0.6f), CircleShape)
                .clickable(onClickLabel = "Expand $title") { host.displayMode("fullscreen") }, contentAlignment = Alignment.Center) {
                Icon(Lucide.Maximize2, Modifier.size(13.dp), tint = color(mutedColor))
            }
        }
        val window = LocalWindowInfo.current.containerSize.height
        val tall = with(LocalDensity.current) { (window * 0.8f).toDp() }.coerceIn(320.dp, 900.dp)
        Popover(expanded, onDismiss = { host.displayMode("inline") }, title = title, maxWidth = 960.dp) {
            if (expanded) HtmlSurfaceView(html, policy, theme, host, true, Modifier.fillMaxWidth().height(tall).clip(Theme[shapes][mediumShape]))
        }
    }
}

/** Kinuvo's light or dark palette, with the Agent's accent, as MCP Apps style variables. */
@Composable
private fun htmlTheme(): HtmlTheme {
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val text = color(onBackgroundColor).css()
    val muted = color(mutedColor).css()
    val border = color(borderColor).css()
    val accent = color(accentColor)
    val panel = color(panelColor).css()
    val subtle = color(subtleColor).css()
    val background = color(backgroundColor).css()
    val tones = mapOf("info" to accent, "success" to color(successColor), "warning" to color(warningColor), "danger" to color(destructiveColor))
    return remember(dark, text, muted, border, panel, subtle, background, tones) {
        HtmlTheme(dark, buildMap {
            put("--color-background-primary", background)
            put("--color-background-secondary", panel)
            put("--color-background-tertiary", subtle)
            put("--color-background-inverse", text)
            put("--color-background-ghost", "transparent")
            put("--color-background-disabled", subtle)
            put("--color-text-primary", text)
            put("--color-text-secondary", muted)
            put("--color-text-tertiary", muted)
            put("--color-text-ghost", muted)
            put("--color-text-disabled", muted)
            put("--color-text-inverse", background)
            put("--color-border-primary", border)
            put("--color-border-secondary", border)
            put("--color-border-tertiary", subtle)
            put("--color-border-ghost", "transparent")
            put("--color-border-inverse", text)
            put("--color-border-disabled", border)
            put("--color-ring-primary", accent.css())
            put("--color-ring-secondary", muted)
            put("--color-ring-inverse", text)
            tones.forEach { (tone, c) ->
                put("--color-text-$tone", c.css())
                put("--color-border-$tone", c.css())
                put("--color-ring-$tone", c.css())
                put("--color-background-$tone", c.copy(alpha = 0.14f).css())
            }
            put("--font-sans", "-apple-system,BlinkMacSystemFont,system-ui,\"Segoe UI\",Roboto,sans-serif")
            put("--font-mono", "ui-monospace,SFMono-Regular,Menlo,monospace")
            put("--font-weight-normal", "400"); put("--font-weight-medium", "500"); put("--font-weight-semibold", "600"); put("--font-weight-bold", "700")
            put("--font-text-sm-size", "13px"); put("--font-text-md-size", "15px"); put("--font-text-lg-size", "17px")
            put("--border-radius-xs", "4px"); put("--border-radius-sm", "8px"); put("--border-radius-md", "12px")
            put("--border-radius-lg", "16px"); put("--border-radius-xl", "20px"); put("--border-radius-full", "9999px")
            put("--border-width-regular", "1px")
        })
    }
}

private fun Color.css(): String {
    fun hex(v: Float) = (v * 255).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + hex(red) + hex(green) + hex(blue) + if (alpha < 1f) hex(alpha) else ""
}

private const val MAX_LIVE = 3

@Composable
private fun HtmlSurfaceView(html: String, policy: HtmlPolicy, theme: HtmlTheme, parent: AppHost, fullscreen: Boolean, modifier: Modifier) {
    // A recreated web document performs a fresh handshake and receives input again.
    val host = remember(parent, fullscreen) { parent.also { it.reconnect() } }
    val bridge = remember(host) { HtmlBridge(host::receive).also { bridge -> host.deliver = { bridge.post(it) } } }
    val document = remember(html, policy, theme, fullscreen) { htmlSurfaceDocument(html, fullscreen, policy, theme) }
    // A new host or theme gets a new web view, which wires its bridge and loads the document only when created.
    key(bridge, document) { HtmlWebView(document, bridge, modifier) }
}

internal expect val htmlSurfacePlatform: String
internal expect fun htmlSurfaceLocale(): String
