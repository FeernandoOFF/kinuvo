package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.composables.ui.theme.ColorScheme
import com.composables.ui.theme.LocalColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kinuvo.protocol.artifact.ArtifactFiles
import kotlinx.serialization.json.JsonPrimitive
import kinuvo.ui.design.ESCAPE_RELAY

/** False in hosts with no native view layer, such as the test scene, where [WebView] on the desktop draws nothing instead of failing. */
internal val LocalNativeViews = staticCompositionLocalOf { true }

/** Where the app talks to a page after it loaded: [send] runs `window.kinuvoReceive(message)` in it, and does nothing until a web view wires it. */
internal class PageChannel {
    var send: (String) -> Unit = {}
}

internal fun pageDelivery(message: String) = "window.kinuvoReceive&&window.kinuvoReceive(${JsonPrimitive(message)});"

/**
 * The platform's own web view showing [html]; one file per platform, so the engine can change in one place. [channel] reaches the page from
 * the app. With [editing] the view leaves panning, zooming and focus to the page, as the editors want; a page that only reports keeps them.
 * With an [origin], the view also serves its site and blocks navigation anywhere else, where [html] is the page that frames it.
 */
@Composable
internal expect fun WebView(html: String, modifier: Modifier = Modifier, onMessage: ((String) -> Unit)? = null, channel: PageChannel? = null, editing: Boolean = onMessage != null, origin: ArtifactOrigin? = null)

/** Whether [WebView] can pass a message from the page, which visual editing needs. */
internal expect val visualEditing: Boolean

/** The files of an Artifact folder: [files] lists them and [read] fetches one by path. */
internal class BundleSource(val files: ArtifactFiles, val read: suspend (String) -> ByteArray?)

/**
 * [html] with scripts but no network requests of its own: only https scripts, styles, fonts and images load. [fade] fades its top edge in from that colour.
 * With [deck], a reveal.js deck in the page is shared through it; only where the web view can pass the page's messages on.
 * With an [artifact] id the page runs at an origin of its own where the platform has one, with real Web Storage; otherwise in a sandboxed frame.
 * With a [bundle] that origin also serves the rest of the folder, fetched when a page asks, with [html] its entry page.
 */
@Composable
internal fun HtmlView(html: String, modifier: Modifier = Modifier, fade: Color? = null, deck: DeckControls? = null, artifact: String? = null, bundle: BundleSource? = null) = key(artifact) {
    val sync = deck != null && visualEditing
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val channel = remember { PageChannel() }
    val receive by rememberUpdatedState(deck?.onMessage)
    LaunchedEffect(deck, channel) { if (sync) deck?.commands?.collect { channel.send(it) } }
    val native = LocalNativeViews.current
    val read by rememberUpdatedState(bundle?.read)
    val site = remember(artifact, html, dark, sync, native, bundle?.files) {
        artifact?.takeIf { native && originId(it) }?.let { id ->
            val page = originPage(html, dark, sync)
            if (bundle == null) ArtifactSite.page(id, page) else ArtifactSite.folder(id, bundle.files, page) { path -> read?.invoke(path) }
        }
    }
    val origin = rememberArtifactOrigin(site)
    val url = origin?.url
    val page = remember(url, html, fade, dark, sync) { if (url != null) originDocument(url, fade, sync) else artifactDocument(html, fade, dark, sync) }
    WebView(page, modifier, if (sync) { text -> receive?.invoke(text) } else null, if (sync) channel else null, editing = false, origin = origin)
}

/**
 * The Content-Security-Policy an Artifact page is held to: [script] sources, [remote] sources for styles, images and fonts, and [frames] it may embed.
 * A page at an origin of its own ([own]) may also load and fetch the files of its own folder, `'self'`, which reaches no other host.
 */
internal fun artifactPolicy(script: String = ARTIFACT_SCRIPTS, remote: String = " https:", frames: String = "", own: Boolean = false): String {
    val self = if (own) " 'self'" else ""
    return "default-src 'none'; script-src $script${if (own) " 'self' 'wasm-unsafe-eval'" else ""}; style-src 'unsafe-inline'$self$remote; img-src$self data: blob:$remote; " +
        "font-src$self data:$remote; media-src$self data: blob:;${if (own) " connect-src 'self';" else ""}${if (frames.isEmpty()) "" else " frame-src $frames;"} form-action 'none'; base-uri 'none'"
}

private const val ARTIFACT_SCRIPTS = "'unsafe-inline' 'unsafe-eval' https:"

/**
 * A trusted head with the policy, then the artifact as is: browsers fold its own `<html>`/`<head>` into this document, so
 * nothing in it (comments, attributes) can move the policy, and it can only add stricter ones. Visual editing drops these
 * marked tags when it saves. [remote] lists the sources styles, images and fonts may also load from; empty blocks the network entirely.
 * With [origin] the page is served from an origin of its own, which has the Web Storage [STORAGE_SHIM] stands in for.
 */
internal fun sandboxed(html: String, script: String = ARTIFACT_SCRIPTS, remote: String = " https:", dark: Boolean? = null, frames: String = "", deck: Boolean = false, origin: Boolean = false) =
    "<!doctype html><html><head><meta data-kinuvo-editor charset=\"utf-8\"><meta data-kinuvo-editor http-equiv=\"Content-Security-Policy\" content=\"${artifactPolicy(script, remote, frames, origin)}\">${dark?.let(::themeHint).orEmpty()}${if ("'unsafe-inline'" in script) "<script data-kinuvo-editor>$ESCAPE_RELAY$HISTORY_SHIM${if (origin) "" else STORAGE_SHIM}${if (deck) DECK_ADAPTER else ""}</script>" else ""}</head>${html.replace(EDITOR_POLICY, "")}"

/** Opaque frames have the app's base URL, so history calls with a URL (reveal.js `hash: true`) throw; they keep the state and drop the URL instead. */
private const val HISTORY_SHIM = "['pushState','replaceState'].forEach(function(n){var f=history[n].bind(history);history[n]=function(s,t,u){try{return f(s,t,u)}catch(e){return f(s,t)}}});"

/**
 * Opaque origins have no Web Storage: touching it throws, which aborts pages mid-setup (reveal.js's scroll view on narrow
 * screens restores its position from sessionStorage). Pages get per-load in-memory stores instead.
 */
private const val STORAGE_SHIM = "['localStorage','sessionStorage'].forEach(function(n){try{window[n].length}catch(e){var m=new Map();" +
    "Object.defineProperty(window,n,{configurable:true,value:{getItem:function(k){k=String(k);return m.has(k)?m.get(k):null}," +
    "setItem:function(k,v){m.set(String(k),String(v))},removeItem:function(k){m.delete(String(k))},clear:function(){m.clear()}," +
    "key:function(i){return Array.from(m.keys())[i]||null},get length(){return m.size}}})}});"

/** The visual editor's policy without its marker, as some saved Artifacts carry it; left in, it blocks every script. */
private val EDITOR_POLICY = Regex("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'nonce-[0-9a-f]{32}';[^>]*>")

/** The artifact as its own page for the system browser, where it may frame itself, as reveal.js speaker notes do. */
internal fun browserDocument(html: String, dark: Boolean) = sandboxed(html, dark = dark, frames = "'self' file: blob: data:")

internal fun artifactDocument(html: String, fade: Color? = null, dark: Boolean? = null, deck: Boolean = false) =
    framed(sandboxed(html, dark = dark, deck = deck), shellExtras(fade, deck))

/** What [openOrigin] serves for an Artifact: its page under the same policy, in the document the frame at the origin loads. */
internal fun originPage(html: String, dark: Boolean, deck: Boolean) = sandboxed(html, dark = dark, deck = deck, origin = true)

/** The page that frames the Artifact served at [url]; it has no origin of the Artifact's, nor the app's. */
internal fun originDocument(url: String, fade: Color? = null, deck: Boolean = false) = framedAt(url, shellExtras(fade, deck))

private fun shellExtras(fade: Color?, deck: Boolean) = fade?.let(::topFade).orEmpty() + if (deck) deckWrapper() else ""

/** The app's theme for the page: `data-theme` on its root and its `color-scheme`, which pages style with `:root[data-theme=dark]`. */
internal fun themeHint(dark: Boolean): String {
    val theme = if (dark) "dark" else "light"
    return "<meta data-kinuvo-editor name=\"color-scheme\" content=\"$theme\">" +
        "<script data-kinuvo-editor>document.documentElement.dataset.theme=\"$theme\"</script>"
}

internal fun topFade(c: Color): String {
    val rgb = "${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()}"
    return "<div style=\"position:fixed;left:0;right:0;top:0;height:24px;pointer-events:none;background:linear-gradient(rgb($rgb),rgba($rgb,0))\"></div>"
}

internal fun attr(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/** [sandbox] adds allow-same-origin only for pages whose scripts are all ours, since sandbox flags pass down and would otherwise wall off their own frames. */
internal fun framed(inner: String, extra: String = "", viewport: String = "width=device-width,initial-scale=1", sandbox: String = "allow-scripts") =
    shell("srcdoc=\"${attr(inner)}\"", extra, viewport, sandbox)

/** The frame loads [url] and keeps the origin it is served from, so its page has real storage; `allow-same-origin` is safe because nothing around the frame shares that origin. */
internal fun framedAt(url: String, extra: String = "", viewport: String = "width=device-width,initial-scale=1") =
    shell("src=\"${attr(url)}\"", extra, viewport, "allow-scripts allow-same-origin")

private fun shell(source: String, extra: String, viewport: String, sandbox: String) =
    "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"$viewport\"></head>" +
        "<body style=\"margin:0;overflow:hidden\"><iframe id=\"f\" title=\"Artifact\" sandbox=\"$sandbox\" referrerpolicy=\"no-referrer\" " +
        "style=\"display:block;border:0;width:100%;height:100vh\" $source></iframe>$extra</body></html>"
