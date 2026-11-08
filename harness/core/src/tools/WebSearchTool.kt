package kinuvo.harness.core.tools

import kinuvo.harness.core.takeWhole
import kinuvo.harness.core.queryWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
    suspend fun search(query: String, limit: Int, news: Boolean, region: SearchRegion? = null): List<WebResult>
    /** The page at [url] as Markdown, for `web_fetch`; null when the service doesn't read pages. */
    suspend fun read(url: String): String? = null
}

data class WebResult(val title: String, val url: String, val snippet: String, val date: String? = null)

class WebSearchTool(private val provider: WebSearch, private val compact: Boolean = false, private val region: SearchRegion? = null) : CoreTool {
    fun compact() = WebSearchTool(provider, compact = true, region = region)

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
        val results = provider.search(query, limit, args["news"]?.jsonPrimitive?.booleanOrNull == true, region)
        if (results.isEmpty()) return "No results for \"$query\"."
        val summary = results.mapIndexed { i, r ->
            listOfNotNull("${i + 1}. ${r.title}", "   ${if (compact) compactUrl(r.url) else r.url}", r.date?.takeUnless { compact }?.let { "   $it" }, (if (compact) cleanSnippet(r.snippet).takeWhole(200) else r.snippet).takeIf { it.isNotBlank() }?.let { "   $it" }).joinToString("\n")
        }.joinToString("\n\n")
        if (!compact) return summary
        val page = try {
            withTimeoutOrNull(6_000) { provider.read(results.first().url) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val passages = page?.let { relevantPassages(it, query) }.orEmpty()
        return if (passages.isBlank()) summary else summary + "\n\nFrom ${compactUrl(results.first().url).substringBefore('/')}:\n$passages"
    }

    private companion object {
        const val MAX = 20
    }
}

internal fun compactUrl(url: String) = url.removePrefix("https://").removePrefix("http://").substringBefore('?').substringBefore('#').takeWhole(100)

/** Snippets some providers return as page markdown, reduced to their words: no links, images, markup or repeated lines. */
internal fun cleanSnippet(text: String): String = text
    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""https?://\S+"""), "")
    .replace(Regex("""[#*_`>|\\]+"""), " ")
    .split('\n').map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }.distinct()
    .joinToString(" · ")

internal fun relevantPassages(markdown: String, query: String): String {
    val words = queryWords(query)
    val ranked = markdown.split(Regex("\\n\\s*\\n")).mapIndexedNotNull { index, paragraph ->
        val text = cleanSnippet(paragraph)
        val score = queryWords(text).intersect(words).size
        if (score == 0) null else Triple(index, text, score)
    }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
    var remaining = 1_200
    val selected = ranked.mapNotNull { (index, text, _) ->
        if (remaining <= 0) null else text.takeWhole(remaining).takeIf { it.isNotBlank() }?.let {
            remaining -= it.length + 2
            index to it
        }
    }
    return selected.sortedBy { it.first }.joinToString("\n\n") { it.second }
}
