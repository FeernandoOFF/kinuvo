package kinuvo.ui.surface

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The https origins an MCP App declared in `_meta.ui.csp`; HTML Surfaces have none, so no network. */
internal data class HtmlPolicy(val connect: List<String> = emptyList(), val resources: List<String> = emptyList()) {
    private fun sources(base: String, extra: List<String>) = (listOf(base) + extra).joinToString(" ")
    fun content(frames: String) = "default-src 'none'; script-src ${sources("'unsafe-inline'", resources)}; style-src ${sources("'unsafe-inline'", resources)}; " +
        "img-src ${sources("data:", resources)}; font-src ${sources("data:", resources)}; media-src ${sources("data:", resources)}; " +
        "connect-src ${connect.joinToString(" ").ifEmpty { "'none'" }}; frame-src $frames; object-src 'none'; base-uri 'none'; form-action 'none'"

    companion object {
        private val origin = Regex("https://(\\*\\.)?[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*(:[0-9]{1,5})?")
        fun of(ui: JsonObject): HtmlPolicy {
            val csp = ui["csp"] as? JsonObject ?: return HtmlPolicy()
            fun list(key: String) = (csp[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trimEnd('/') }
                .filter(origin::matches).distinct().take(32)
            return HtmlPolicy(list("connectDomains"), list("resourceDomains"))
        }
    }
}

/** The chat's colors and type as the MCP Apps style variables, so pages blend with the chat in light and dark. */
internal data class HtmlTheme(val dark: Boolean = false, val variables: Map<String, String> = emptyMap()) {
    val background get() = variables["--color-background-primary"] ?: "transparent"
    val css get() = ":root{color-scheme:${if (dark) "dark" else "light"};" + variables.entries.joinToString("") { (k, v) -> "$k:$v;" } + "}" +
        "html,body{background:var(--color-background-primary,transparent);color:var(--color-text-primary);font-family:var(--font-sans);-webkit-text-size-adjust:100%;touch-action:manipulation}" +
        "button,input,select,textarea{font:inherit}a{color:var(--color-text-info)}"
}

private const val VIEWPORT = "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"

internal fun htmlSurfaceDocument(html: String, fullscreen: Boolean, policy: HtmlPolicy = HtmlPolicy(), theme: HtmlTheme = HtmlTheme()): String {
    val csp = policy.content("'none'")
    val reporter = """<script>
        addEventListener('DOMContentLoaded',()=>{
          const report=()=>parent.postMessage({jsonrpc:'2.0',method:'ui/notifications/size-changed',params:{height:Math.max(40,document.body?.scrollHeight||0,Math.ceil(document.body?.getBoundingClientRect().height||0))}},'*');
          const observer=new ResizeObserver(report);observer.observe(document.documentElement);observer.observe(document.body);report();
        });
    </script>"""
    // The theme comes first, so the page's own styles win.
    val app = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"$VIEWPORT\"><meta http-equiv=\"Content-Security-Policy\" content=\"$csp\"><style>${theme.css}html,body{margin:0;overflow:${if (fullscreen) "auto" else "hidden"} !important}</style>$reporter</head>$html"
    return """<!doctype html><html><head><meta name="viewport" content="$VIEWPORT">
      <meta http-equiv="Content-Security-Policy" content="${policy.content("about:")}">
      <style>:root{color-scheme:${if (theme.dark) "dark" else "light"}}html,body{background:${theme.background};touch-action:manipulation}</style>
      </head><body style="margin:0;overflow:hidden"><iframe id="app" title="HTML Surface" sandbox="allow-scripts" referrerpolicy="no-referrer" style="position:absolute;inset:0;border:0;width:100%;height:100%;background:${theme.background};color-scheme:${if (theme.dark) "dark" else "light"}"></iframe>
      <script>
      const app=document.getElementById('app');
      window.kinuvoReceive=raw=>app.contentWindow.postMessage(JSON.parse(raw),'*');
      addEventListener('message',event=>{
        if(event.source===parent&&parent!==window&&typeof event.data==='string'){window.kinuvoReceive(event.data);return;}
        if(event.source!==app.contentWindow||!event.data||event.data.jsonrpc!=='2.0')return;
        const raw=JSON.stringify(event.data);
        if(window.kinuvoNative)window.kinuvoNative.postMessage(raw);
        else if(window.webkit?.messageHandlers?.kinuvoNative)window.webkit.messageHandlers.kinuvoNative.postMessage(raw);
        else parent.postMessage(raw,'*');
      });
      app.srcdoc=${JsonPrimitive(app).toString().replace("<", "\\u003c")};
      </script></body></html>"""
}

internal fun htmlSurfaceDelivery(message: String) = "window.kinuvoReceive(${JsonPrimitive(message)});"
