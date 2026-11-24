package kinuvo.providers.codex

import io.ktor.client.request.isSseRequest
import kotlinx.coroutines.flow.first
import io.ktor.client.plugins.sse.sse
import kinuvo.protocol.usage.Usage

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexHttpClientTest {
    @Test fun routesPlanUsageToPublicApiWithoutCodexHeaders() = runTest {
        var sent: String? = null
        val client = codexHttpClient({ CodexTokens("siwc", "", true) }, MutableStateFlow(null), MockEngine { req ->
            assertEquals("https://api.openai.com/v1/responses", req.url.toString())
            assertEquals("Bearer siwc", req.headers[HttpHeaders.Authorization])
            assertNull(req.headers["chatgpt-account-id"])
            assertNull(req.headers["OpenAI-Beta"])
            sent = (req.body as TextContent).text
            respond("ok")
        })
        client.post("responses") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","temperature":1,"store":true,"stream":false,"input":[]}""")
        }
        val body = Json.parseToJsonElement(sent!!).jsonObject
        assertEquals("false", body["store"].toString())
        assertEquals("true", body["stream"].toString())
        assertNull(body["temperature"])
        client.close()
    }

    @Test
    fun retriesOnceWithRefreshedTokenAndCapturesUsage() = runTest {
        val refreshes = mutableListOf<Boolean>()
        val seenAuth = mutableListOf<String?>()
        val engine = MockEngine { req ->
            seenAuth += req.headers[HttpHeaders.Authorization]
            assertEquals("acct", req.headers["chatgpt-account-id"])
            if (seenAuth.size == 1) respond("", HttpStatusCode.Unauthorized)
            else respond("ok", HttpStatusCode.OK, headersOf("x-codex-primary-used-percent", "42"))
        }
        val usage = MutableStateFlow<Usage?>(null)
        val client = codexHttpClient(
            tokens = { force -> refreshes += force; CodexTokens(if (force) "new" else "old", "acct") },
            usage = usage,
            engine = engine,
        ).config { } // Koog wraps the client this way; auth must survive it

        val status = client.get("responses").status
        assertEquals(HttpStatusCode.OK, status)

        assertEquals(listOf<String?>("Bearer old", "Bearer new"), seenAuth)
        assertEquals(listOf(false, true), refreshes)
        assertEquals(42.0, usage.value?.windows?.firstOrNull()?.usedPercent)
    }

    @Test
    fun sendsFunctionToolsAsNonStrict() = runTest {
        var sent: String? = null
        val engine = MockEngine { req -> sent = (req.body as TextContent).text; respond("ok") }
        val client = codexHttpClient({ CodexTokens("t", "acct") }, MutableStateFlow(null), engine).config { }

        // A String body, as Koog sends it.
        client.post("responses") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","tools":[{"type":"function","name":"freeagent__timeslips","parameters":{"type":"object","properties":{"project_id":{"type":"string"}}}},{"type":"function","name":"x","parameters":{},"strict":true}]}""")
        }

        val tools = Json.parseToJsonElement(sent!!).jsonObject.getValue("tools").jsonArray
        assertEquals(listOf("false", "true"), tools.map { it.jsonObject.getValue("strict").toString() })
        assertNull(nonStrictTools("""{"input":[]}"""))
    }

    @Test
    fun chatGptPlanRequestGroupsFunctionsUnderNamespaceAndTagsEchoedCalls() {
        val body = """{"model":"m","input":[{"role":"system","content":"hi"},{"type":"function_call","call_id":"c1","name":"freeagent__timeslips","arguments":"{}"},{"type":"message","role":"user","content":"hi"}],"tools":[{"type":"function","name":"freeagent__timeslips","parameters":{}}]}"""
        val result = Json.parseToJsonElement(chatGptPlanRequest(body)!!).jsonObject

        val tools = result.getValue("tools").jsonArray
        assertEquals(1, tools.size)
        val namespace = tools.single()
        assertEquals("\"namespace\"", namespace.jsonObject.getValue("type").toString())
        assertEquals("\"kinuvo\"", namespace.jsonObject.getValue("name").toString())
        assertEquals(1, namespace.jsonObject.getValue("tools").jsonArray.size)

        val input = result.getValue("input").jsonArray
        assertEquals("\"developer\"", input[0].jsonObject.getValue("role").toString())
        assertEquals("\"kinuvo\"", input[1].jsonObject.getValue("namespace").toString())
        assertNull(input[2].jsonObject["namespace"])
    }

    @Test
    fun chatGptPlanRequestLeavesExistingNamespaceOnEchoedCallsAlone() {
        val body = """{"model":"m","input":[{"type":"function_call","call_id":"c1","name":"freeagent__timeslips","namespace":"kinuvo","arguments":"{}"}],"tools":[{"type":"function","name":"freeagent__timeslips","parameters":{}}]}"""
        val input = Json.parseToJsonElement(chatGptPlanRequest(body)!!).jsonObject.getValue("input").jsonArray
        assertEquals("\"kinuvo\"", input[0].jsonObject.getValue("namespace").toString())
    }

    @Test
    fun rewriteSseLinePassesThroughNonMatchingLines() {
        assertEquals("event: response.created", rewriteSseLine("event: response.created"))
        assertEquals("", rewriteSseLine(""))
        assertEquals("data: [DONE]", rewriteSseLine("data: [DONE]"))
        assertEquals("data: not json", rewriteSseLine("data: not json"))
        val noNamespace = """data: {"type":"response.created","response":{"tools":[{"type":"function","name":"echo"}]}}"""
        assertEquals(noNamespace, rewriteSseLine(noNamespace))
    }

    @Test
    fun rewriteSseLineFlattensNamespaceToolsInResponseEvents() {
        val line = """data: {"type":"response.created","response":{"tools":[{"type":"namespace","name":"kinuvo","description":"Kinuvo tools","tools":[{"type":"function","name":"echo","parameters":{}}]},{"type":"web_search"}]}}"""
        val rewritten = rewriteSseLine(line)

        assertFalse(rewritten.contains("namespace"))
        val tools = Json.parseToJsonElement(rewritten.removePrefix("data: ")).jsonObject.getValue("response").jsonObject.getValue("tools").jsonArray
        assertEquals(listOf("\"function\"", "\"web_search\""), tools.map { it.jsonObject.getValue("type").toString() })
        assertEquals("\"echo\"", tools[0].jsonObject.getValue("name").toString())
    }

    @Test
    fun namespaceStreamFixFlattensOnlyApiOpenaiComSseResponses() = runTest {
        val sseBody = "event: response.created\n" +
            """data: {"type":"response.created","response":{"tools":[{"type":"namespace","name":"kinuvo","description":"d","tools":[{"type":"function","name":"echo","parameters":{}}]}]}}""" + "\n\n"

        suspend fun fetch(url: String) = HttpClient(NamespaceToolStreamFix(MockEngine { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
        })).use { it.get(url).bodyAsText() }

        val openAiBody = fetch("https://api.openai.com/v1/responses")
        assertFalse(openAiBody.contains("namespace"))
        assertTrue(openAiBody.contains("\"type\":\"function\""))

        // The codex backend path must stay untouched.
        val oldBackendBody = fetch("https://chatgpt.com/backend-api/codex/responses")
        assertTrue(oldBackendBody.contains("\"type\":\"namespace\""))

        // A plain (non-SSE-content-typed) api.openai.com response, e.g. "models", is also left alone.
        val modelsBody = HttpClient(NamespaceToolStreamFix(MockEngine { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })).use { it.get("https://api.openai.com/v1/models").bodyAsText() }
        assertTrue(modelsBody.contains("\"type\":\"namespace\""))
    }

    /** Real engines (Darwin, OkHttp, Java) return SSE responses as an already-parsed session, not raw bytes. */
    @Suppress("DEPRECATION")
    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class SessionEngine(private val delegate: io.ktor.client.engine.HttpClientEngine) : io.ktor.client.engine.HttpClientEngineBase("session") {
        override val config = delegate.config
        override val supportedCapabilities = delegate.supportedCapabilities + io.ktor.client.plugins.sse.SSECapability
        override suspend fun execute(data: io.ktor.client.request.HttpRequestData): io.ktor.client.request.HttpResponseData {
            val res = delegate.execute(data)
            val body = res.body
            if (!data.isSseRequest() || body !is io.ktor.utils.io.ByteReadChannel) return res
            return io.ktor.client.request.HttpResponseData(res.statusCode, res.requestTime, res.headers, res.version,
                io.ktor.client.plugins.sse.DefaultClientSSESession(data.body as io.ktor.client.plugins.sse.SSEClientContent, body), res.callContext)
        }
    }

    @Test
    fun namespaceStreamFixRewritesParsedSessions() = runTest {
        val event = """{"type":"response.created","response":{"tools":[{"type":"namespace","name":"kinuvo","tools":[{"type":"function","name":"echo","parameters":{}}]}]}}"""
        val engine = NamespaceToolStreamFix(SessionEngine(MockEngine { _ ->
            respond("event: response.created\ndata: $event\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
        }))
        val data = HttpClient(engine) { install(io.ktor.client.plugins.sse.SSE) }.use { client ->
            var first: String? = null
            client.sse("https://api.openai.com/v1/responses") { first = incoming.first().data }
            first
        }
        assertFalse(data!!.contains("namespace"))
        assertTrue(data.contains("\"name\":\"echo\""))
    }

    @Test
    fun planRequestsAnswerInterruptedToolCalls() {
        val body = """{"input":[{"type":"function_call","call_id":"a","name":"echo","arguments":"{}"},{"type":"function_call","call_id":"b","name":"echo","arguments":"{}"},{"type":"function_call_output","call_id":"b","output":"ok"},{"role":"user","content":"Try again."}]}"""
        val input = Json.parseToJsonElement(chatGptPlanRequest(body)!!).jsonObject.getValue("input").jsonArray
        assertEquals(listOf("function_call", "function_call_output", "function_call", "function_call_output", null),
            input.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull })
        assertEquals("a", input[1].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals(1, input.count { it.jsonObject["call_id"]?.jsonPrimitive?.contentOrNull == "b" && it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
    }
}
