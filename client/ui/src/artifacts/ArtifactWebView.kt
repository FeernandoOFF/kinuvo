package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonPrimitive

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
internal fun HtmlView(html: String, modifier: Modifier = Modifier, fade: Color? = null) = WebView(artifactDocument(html, fade), modifier)

private fun csp(script: String) = "<meta data-kinuvo-editor http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
    "script-src $script; style-src 'unsafe-inline' https:; img-src data: blob: https:; " +
    "font-src data: https:; media-src data: blob:; form-action 'none'; base-uri 'none'\">"

private const val ARTIFACT_SCRIPTS = "'unsafe-inline' 'unsafe-eval' https:"

/**
 * A trusted head with the policy, then the artifact as is: browsers fold its own `<html>`/`<head>` into this document, so
 * nothing in it (comments, attributes) can move the policy, and it can only add stricter ones. Visual editing drops these
 * marked tags when it saves.
 */
internal fun sandboxed(html: String, script: String = ARTIFACT_SCRIPTS) =
    "<!doctype html><html><head><meta data-kinuvo-editor charset=\"utf-8\">${csp(script)}</head>${html.replace(EDITOR_POLICY, "")}"

/** The visual editor's policy without its marker, as some saved Artifacts carry it; left in, it blocks every script. */
private val EDITOR_POLICY = Regex("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'nonce-[0-9a-f]{32}';[^>]*>")

internal fun artifactDocument(html: String, fade: Color? = null) = framed(sandboxed(html), fade?.let(::topFade).orEmpty())

internal fun topFade(c: Color): String {
    val rgb = "${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()}"
    return "<div style=\"position:fixed;left:0;right:0;top:0;height:24px;pointer-events:none;background:linear-gradient(rgb($rgb),rgba($rgb,0))\"></div>"
}

internal fun attr(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/** [sandbox] adds allow-same-origin only for pages whose scripts are all ours, since sandbox flags pass down and would otherwise wall off their own frames. */
internal fun framed(inner: String, extra: String = "", viewport: String = "width=device-width,initial-scale=1", sandbox: String = "allow-scripts"): String {
    val content = attr(inner)
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"$viewport\"></head>" +
        "<body style=\"margin:0\"><iframe id=\"f\" title=\"Artifact\" sandbox=\"$sandbox\" referrerpolicy=\"no-referrer\" " +
        "style=\"border:0;width:100%;height:100vh\" srcdoc=\"$content\"></iframe>$extra</body></html>"
}
