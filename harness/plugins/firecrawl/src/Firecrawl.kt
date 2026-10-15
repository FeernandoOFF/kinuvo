package kinuvo.harness.firecrawl

import kinuvo.harness.core.*
import kinuvo.harness.core.tools.WebResult
import kinuvo.harness.core.tools.WebSearch
import kinuvo.harness.core.tools.WebSearchProvider
import kinuvo.protocol.*
import kotlinx.serialization.json.*

class FirecrawlPlugin : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.firecrawl", "Firecrawl")
    override val webSearch = listOf(object : WebSearchProvider {
        override val id = "firecrawl"
        override val name = "Firecrawl"
        override val settings = SettingsSchema(listOf(
            SettingsField("apiKey", "API key", description = "From firecrawl.dev → API keys.", placeholder = "fc-…", required = true, sensitive = true),
            SettingsField("url", "Server URL", SettingsFieldType.Url, "Only for a self-hosted Firecrawl.", defaultValue = Firecrawl.DEFAULT_URL),
        ))
        override val hostAccess = PluginAccess(network = setOf("url"), secrets = setOf("apiKey"))
        override fun configured(options: Map<String, String>, host: PluginHost): WebSearch {
            checkOptions(options)
            return Firecrawl(host)
        }
    })
    override val manifest get() = super.manifest.copy(discoversTools = false)
}

/** Search and page extraction through host-bound HTTP and credentials. */
class Firecrawl(private val host: PluginHost) : WebSearch {
    override suspend fun search(query: String, limit: Int, news: Boolean): List<WebResult> {
        val source = if (news) "news" else "web"
        val data = call("search", buildJsonObject {
            put("query", query)
            put("limit", limit)
            putJsonArray("sources") { add(source) }
        })
        return (data?.get(source) as? JsonArray).orEmpty().map { it.jsonObject }.map {
            WebResult(it.text("title"), it.text("url"), it.text("description").ifEmpty { it.text("snippet") }, it.text("date").ifEmpty { null })
        }
    }

    override suspend fun read(url: String): String? {
        val data = call("scrape", buildJsonObject {
            put("url", url)
            putJsonArray("formats") { add("markdown") }
            put("onlyMainContent", true)
        }) ?: return null
        val markdown = data.text("markdown").ifBlank { return null }
        val meta = data["metadata"] as? JsonObject
        val title = meta?.text("title")?.ifBlank { null }
        val status = meta?.get("statusCode")?.jsonPrimitive?.intOrNull
        return listOfNotNull(status?.let { "HTTP $it" }, title?.let { "# $it" }, markdown).joinToString("\n\n")
    }

    private suspend fun call(endpoint: String, body: JsonObject): JsonObject? {
        val res = host.http(PluginHttpRequest(URL, listOf("v2", endpoint), "POST",
            headers = mapOf("Authorization" to "Bearer ${host.secret(KEY)}", "Content-Type" to "application/json"), body = body.toString()))
        val text = res.body
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (res.status !in 200..299 || json?.get("success")?.jsonPrimitive?.booleanOrNull == false) {
            error("Firecrawl $endpoint failed (HTTP ${res.status}): ${json?.text("error")?.ifBlank { null } ?: text.take(300)}")
        }
        return json?.get("data") as? JsonObject
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val DEFAULT_URL = "https://api.firecrawl.dev"
        const val KEY = "apiKey"
        const val URL = "url"
    }
}
