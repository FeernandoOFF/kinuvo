package kinuvo.server.tools

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.WebSearch
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.takeWhole
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** [http] should follow redirects and time out; the app picks its engine. [reader] returns pages as Markdown when set up, e.g. Firecrawl. */
class WebFetchTool(
    private val http: HttpClient,
    private val maxChars: Int = 20_000,
    private val reader: WebSearch? = null,
) : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.WEB_FETCH,
        description = "Fetch a URL over HTTP(S) and return its text content (HTML is reduced to plain text).",
        requiredParameters = listOf(ToolParameter("url", "Absolute http(s) URL", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val url = args.requireString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "Only http(s) URLs are allowed" }
        reader?.let { r ->
            runCatching { r.read(url) }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.let { return clip(it) }
        }
        val res = http.get(url)
        val body = res.bodyAsText()
        val text = if (res.contentType()?.contentSubtype == "html") htmlToText(body) else body
        check(res.status.value < 400) { "HTTP ${res.status.value}\n\n${clip(text).takeWhole(2_000)}" }
        return "HTTP ${res.status.value}\n\n${clip(text)}"
    }

    private fun clip(text: String) = if (text.length > maxChars) text.takeWhole(maxChars) + "\n…[truncated]" else text

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()
}
