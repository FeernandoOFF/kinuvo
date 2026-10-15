package kinuvo.harness.codex

import kinuvo.protocol.Usage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexHttpClientTest {
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
}
