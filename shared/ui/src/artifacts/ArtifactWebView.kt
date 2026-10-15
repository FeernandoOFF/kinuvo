package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The platform's own web view showing [html]; one file per platform, so the engine can change in one place. */
@Composable
internal expect fun WebView(html: String, modifier: Modifier = Modifier)

/** [html] with scripts but no network requests of its own: only https scripts, styles, fonts and images load. */
@Composable
internal fun HtmlView(html: String, modifier: Modifier = Modifier) = WebView(artifactDocument(html), modifier)

private const val CSP = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
    "script-src 'unsafe-inline' 'unsafe-eval' https:; style-src 'unsafe-inline' https:; img-src data: blob: https:; " +
    "font-src data: https:; media-src data: blob:; form-action 'none'; base-uri 'none'\">"

/**
 * A trusted head with the policy, then the artifact as is: browsers fold its own `<html>`/`<head>` into this document, so
 * nothing in it (comments, attributes) can move the policy, and it can only add stricter ones.
 */
internal fun sandboxed(html: String) = "<!doctype html><html><head><meta charset=\"utf-8\">$CSP</head>$html"

internal fun artifactDocument(html: String): String {
    val content = sandboxed(html).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
        "<body style=\"margin:0\"><iframe title=\"Artifact\" sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" " +
        "style=\"border:0;width:100%;height:100vh\" srcdoc=\"$content\"></iframe></body></html>"
}
