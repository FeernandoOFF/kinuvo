package kinuvo.server.plugin

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
