package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.Risk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A web search service, e.g. Firecrawl; set up from `search.yaml`. */
interface WebSearchProvider : kinuvo.harness.core.ConfigurableProvider {
    /** The provider with [options], whose values may hold `${…}` references; fails when a required one is missing. */
    fun configured(options: Map<String, String>, host: kinuvo.harness.core.PluginHost): WebSearch
}

interface WebSearch {
    suspend fun search(query: String, limit: Int, news: Boolean): List<WebResult>
    /** The page at [url] as Markdown, for `web_fetch`; null when the service doesn't read pages. */
    suspend fun read(url: String): String? = null
}

data class WebResult(val title: String, val url: String, val snippet: String, val date: String? = null)

class WebSearchTool(private val provider: WebSearch) : Tool {
    override val risk = Risk.Low

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
            listOfNotNull("${i + 1}. ${r.title}", "   ${r.url}", r.date?.let { "   $it" }, r.snippet.takeIf { it.isNotBlank() }?.let { "   $it" }).joinToString("\n")
        }.joinToString("\n\n")
    }

    private companion object {
        const val MAX = 20
    }
}
