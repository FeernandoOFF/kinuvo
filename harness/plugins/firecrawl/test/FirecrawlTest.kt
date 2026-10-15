package kinuvo.harness.firecrawl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kinuvo.harness.core.tools.WebSearchTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kinuvo.harness.core.*
import kinuvo.protocol.*

class FirecrawlTest {
    private val hosts = mutableListOf<PluginHosts>()
    private val requests = mutableListOf<Pair<String, JsonObject>>()
    private var reply = ""
    private var status = HttpStatusCode.OK
    private val http = HttpClient(MockEngine { req ->
        assertEquals("Bearer secret", req.headers[HttpHeaders.Authorization])
        requests += req.url.toString() to Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val key = "\${pass://Kinuvo/Firecrawl/key}"
    private val firecrawl = firecrawl(mapOf("apiKey" to key)) { if (it == key) "secret" else it }

    @AfterTest fun close() { hosts.forEach { it.close() }; http.close() }

    private fun firecrawl(options: Map<String, String>, expand: suspend (String) -> String = { it }): kinuvo.harness.core.tools.WebSearch {
        val plugin = FirecrawlPlugin()
        val provider = plugin.webSearch.single()
        provider.checkOptions(options)
        val effective = provider.effectiveOptions(options)
        val runtime = PluginHosts(http) { _, value -> expand(value) }.also { hosts += it }
        val grants = PluginGrants(plugin.instance,
            network = effective.filterKeys { it in provider.hostAccess.network },
            secrets = effective.filterKeys { it in provider.hostAccess.secrets })
        return provider.configured(effective, runtime.bind(plugin.manifest, plugin.instance) { grants })
    }

    @Test
    fun searchesTheWebAndNews() = runTest {
        reply = """{"success":true,"data":{"web":[{"title":"Kotlin","url":"https://kotlinlang.org","description":"A language"}]}}"""
        val out = WebSearchTool(firecrawl).execute(buildJsonObject { put("query", "kotlin"); put("limit", 3) })
        assertEquals("1. Kotlin\n   https://kotlinlang.org\n   A language", out)
        assertEquals("https://api.firecrawl.dev/v2/search", requests.last().first)
        assertEquals("""{"query":"kotlin","limit":3,"sources":["web"]}""", requests.last().second.toString())

        reply = """{"success":true,"data":{"news":[{"title":"KotlinConf","url":"https://n.example","snippet":"Talks","date":"2 days ago"}]}}"""
        assertEquals("KotlinConf" to "2 days ago", firecrawl.search("kotlinconf", 5, news = true).single().let { it.title to it.date })
    }

    @Test
    fun readsPagesAsMarkdownFromASelfHostedServer() = runTest {
        val selfHosted = firecrawl(mapOf("apiKey" to "secret", "url" to "\${FIRECRAWL_URL}")) { if (it == "\${FIRECRAWL_URL}") "http://localhost:3002/" else it }
        reply = """{"success":true,"data":{"markdown":"Hello","metadata":{"title":"Home","statusCode":200}}}"""
        assertEquals("HTTP 200\n\n# Home\n\nHello", selfHosted.read("https://example.org"))
        assertEquals("http://localhost:3002/v2/scrape", requests.last().first)
    }

    @Test
    fun reportsErrorsAndMissingKeys() = runTest {
        status = HttpStatusCode.PaymentRequired
        reply = """{"success":false,"error":"Insufficient credits"}"""
        assertEquals("Firecrawl search failed (HTTP 402): Insufficient credits", assertFailsWith<IllegalStateException> { firecrawl.search("x", 1, false) }.message)
        assertFailsWith<IllegalArgumentException> { firecrawl(mapOf("apiKey" to " ")) }
    }

    @Test fun validatesInterpolatedUrlsBeforeSendingCredentials() = runTest {
        reply = """{"success":true,"data":{"web":[]}}"""
        val options = mapOf("apiKey" to "secret", "url" to "\${FIRECRAWL_URL}/proxy")
        firecrawl(options) { it.replace("\${FIRECRAWL_URL}", "https://search.example.org") }.search("test", 1, false)
        assertEquals("https://search.example.org/proxy/v2/search", requests.single().first)
        assertFailsWith<IllegalArgumentException> {
            firecrawl(options) { it.replace("\${FIRECRAWL_URL}", "file:///tmp") }.search("test", 1, false)
        }
        assertEquals(1, requests.size)
    }
}
