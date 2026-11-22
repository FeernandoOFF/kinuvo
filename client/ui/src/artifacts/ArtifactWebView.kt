package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.composables.ui.theme.ColorScheme
import com.composables.ui.theme.LocalColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonPrimitive
import kinuvo.ui.design.ESCAPE_RELAY

/** False in hosts with no native view layer, such as the test scene, where [WebView] on the desktop draws nothing instead of failing. */
internal val LocalNativeViews = staticCompositionLocalOf { true }

/** Where the app talks to a page after it loaded: [send] runs `window.kinuvoReceive(message)` in it, and does nothing until a web view wires it. */
internal class PageChannel {
    var send: (String) -> Unit = {}
}

internal fun pageDelivery(message: String) = "window.kinuvoReceive&&window.kinuvoReceive(${JsonPrimitive(message)});"

/** The platform's own web view showing [html]; one file per platform, so the engine can change in one place. [channel] reaches the page from the app. */
@Composable
internal expect fun WebView(html: String, modifier: Modifier = Modifier, onMessage: ((String) -> Unit)? = null, channel: PageChannel? = null)

/** Whether [WebView] can pass a message from the page, which visual editing needs. */
internal expect val visualEditing: Boolean

/** [html] with scripts but no network requests of its own: only https scripts, styles, fonts and images load. [fade] fades its top edge in from that colour. */
@Composable
internal fun HtmlView(html: String, modifier: Modifier = Modifier, fade: Color? = null) =
    WebView(artifactDocument(html, fade, LocalColorScheme.current == ColorScheme.Dark), modifier)

private fun csp(script: String, remote: String, frames: String) = "<meta data-kinuvo-editor http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
    "script-src $script; style-src 'unsafe-inline'$remote; img-src data: blob:$remote; " +
    "font-src data:$remote; media-src data: blob:;${if (frames.isEmpty()) "" else " frame-src $frames;"} form-action 'none'; base-uri 'none'\">"

private const val ARTIFACT_SCRIPTS = "'unsafe-inline' 'unsafe-eval' https:"

/**
 * A trusted head with the policy, then the artifact as is: browsers fold its own `<html>`/`<head>` into this document, so
 * nothing in it (comments, attributes) can move the policy, and it can only add stricter ones. Visual editing drops these
 * marked tags when it saves. [remote] lists the sources styles, images and fonts may also load from; empty blocks the network entirely.
 */
internal fun sandboxed(html: String, script: String = ARTIFACT_SCRIPTS, remote: String = " https:", dark: Boolean? = null, frames: String = "") =
    "<!doctype html><html><head><meta data-kinuvo-editor charset=\"utf-8\">${csp(script, remote, frames)}${dark?.let(::themeHint).orEmpty()}${if ("'unsafe-inline'" in script) "<script data-kinuvo-editor>$ESCAPE_RELAY$HISTORY_SHIM</script>" else ""}</head>${html.replace(EDITOR_POLICY, "")}"

/** Frames have an opaque origin but the app's base URL, so history calls with a URL (reveal.js `hash: true`) throw; they keep the state and drop the URL instead. */
private const val HISTORY_SHIM = "['pushState','replaceState'].forEach(function(n){var f=history[n].bind(history);history[n]=function(s,t,u){try{return f(s,t,u)}catch(e){return f(s,t)}}});"

/** The visual editor's policy without its marker, as some saved Artifacts carry it; left in, it blocks every script. */
private val EDITOR_POLICY = Regex("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'nonce-[0-9a-f]{32}';[^>]*>")

/** The artifact as its own page for the system browser, where it may frame itself, as reveal.js speaker notes do. */
internal fun browserDocument(html: String, dark: Boolean) = sandboxed(html, dark = dark, frames = "'self' file: blob: data:")

internal fun artifactDocument(html: String, fade: Color? = null, dark: Boolean? = null) = framed(sandboxed(html, dark = dark), fade?.let(::topFade).orEmpty())

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
internal fun framed(inner: String, extra: String = "", viewport: String = "width=device-width,initial-scale=1", sandbox: String = "allow-scripts"): String {
    val content = attr(inner)
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"$viewport\"></head>" +
        "<body style=\"margin:0;overflow:hidden\"><iframe id=\"f\" title=\"Artifact\" sandbox=\"$sandbox\" referrerpolicy=\"no-referrer\" " +
        "style=\"display:block;border:0;width:100%;height:100vh\" srcdoc=\"$content\"></iframe>$extra</body></html>"
}
