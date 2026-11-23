package kinuvo.server.jvm

import kinuvo.protocol.plugin.PluginAccess
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.protocol.plugin.PluginHttpRequest
import kinuvo.protocol.settings.SettingsField
import kinuvo.protocol.settings.SettingsFieldType
import kinuvo.protocol.settings.SettingsSchema
import kinuvo.server.plugin.PluginHost
import kinuvo.server.plugin.SearchRegion
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.WebResult
import kinuvo.server.plugin.WebSearch
import kinuvo.server.plugin.WebSearchProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ReviewSearchPlugin : ServerPlugin {
    override val definition = PluginDefinition("kinuvo.review-search", "Review web search")
    override val webSearch = listOf(object : WebSearchProvider {
        override val id = "review-search"
        override val settings = SettingsSchema(listOf(SettingsField("url", "Search URL", SettingsFieldType.Url, required = true)))
        override val hostAccess = PluginAccess(network = setOf("url"))
        override fun configured(options: Map<String, String>, host: PluginHost): WebSearch {
            checkOptions(options)
            return object : WebSearch {
                override suspend fun search(query: String, limit: Int, news: Boolean, region: SearchRegion?): List<WebResult> {
                    val response = host.http(PluginHttpRequest("url", listOf("search"), query = mapOf(
                        "q" to query, "format" to "json", "categories" to if (news) "news" else "general")))
                    check(response.status == 200) { "Review web search is temporarily unavailable" }
                    return Json.parseToJsonElement(response.body).jsonObject["results"]?.jsonArray.orEmpty().take(limit.coerceIn(1, 20)).map {
                        val result = it.jsonObject
                        WebResult(result["title"]?.jsonPrimitive?.content.orEmpty(), result.getValue("url").jsonPrimitive.content,
                            result["content"]?.jsonPrimitive?.content.orEmpty())
                    }
                }
            }
        }
    })
}
