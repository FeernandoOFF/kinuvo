package kinuvo.harness.core.tools

import kinuvo.harness.core.takeWhole
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kinuvo.harness.core.plugin.ConfigurableProvider
import kinuvo.harness.core.plugin.PluginHost

/** A web search service, e.g. Firecrawl; set up in `config.yaml`. */
interface WebSearchProvider : ConfigurableProvider {
    /** The provider with [options], whose values may hold `${…}` references; fails when a required one is missing. */
    fun configured(options: Map<String, String>, host: PluginHost): WebSearch
}

interface WebSearch {
    suspend fun search(query: String, limit: Int, news: Boolean): List<WebResult>
    /** The page at [url] as Markdown, for `web_fetch`; null when the service doesn't read pages. */
    suspend fun read(url: String): String? = null
}

data class WebResult(val title: String, val url: String, val snippet: String, val date: String? = null)

class WebSearchTool(private val provider: WebSearch, private val compact: Boolean = false) : CoreTool {
    fun compact() = WebSearchTool(provider, compact = true)

    override val descriptor = ToolDescriptor(
        name = ToolNames.WEB_SEARCH,
        description = "Search the web. Returns titles, URLs and snippets; read a result with web_fetch.",
        requiredParameters = listOf(ToolParameterDescriptor("query", "What to search for", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("limit", "Number of results (default 5, max $MAX)", ToolParameterType.Integer),
            ToolParameterDescriptor("news", "Search recent news instead of the web (default false)", ToolParameterType.Boolean),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val query = args.requireString("query")
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, MAX)
        val results = provider.search(query, limit, args["news"]?.jsonPrimitive?.booleanOrNull == true)
        if (results.isEmpty()) return "No results for \"$query\"."
        return results.mapIndexed { i, r ->
            listOfNotNull("${i + 1}. ${r.title}", "   ${if (compact) compactUrl(r.url) else r.url}", r.date?.takeUnless { compact }?.let { "   $it" }, (if (compact) r.snippet.takeWhole(200) else r.snippet).takeIf { it.isNotBlank() }?.let { "   $it" }).joinToString("\n")
        }.joinToString("\n\n")
    }

    private companion object {
        const val MAX = 20
    }
}

internal fun compactUrl(url: String) = url.removePrefix("https://").removePrefix("http://").substringBefore('?').substringBefore('#').takeWhole(100)
