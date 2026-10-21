package kinuvo.harness.codex

import kinuvo.harness.testing.*
import ai.koog.agents.core.tools.ToolDescriptor
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.InternalAPI
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(InternalAPI::class)
class OpenAIProviderTest {
    @Test
    fun discoversModelsAndUsesApiAuthenticationForResponses() = runTest {
        val key: suspend () -> String? = { "test-key" }
        var sent: JsonObject? = null
        val engine = MockEngine { request ->
            assertEquals("api.openai.com", request.url.host)
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["chatgpt-account-id"])
            if (request.url.encodedPath == "/v1/models") respond("""{"data":[{"id":"gpt-test"},{"id":"text-embedding-3-small"},{"id":"gpt-audio"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                assertEquals("/v1/responses", request.url.encodedPath)
                fun body(content: io.ktor.http.content.OutgoingContent): String = when (content) {
                    is TextContent -> content.text
                    is io.ktor.http.content.OutgoingContent.ContentWrapper -> body(content.delegate())
                    else -> error("Unexpected request body")
                }
                sent = Json.parseToJsonElement(body(request.body)).jsonObject
                respond("""{"error":{"message":"invalid test key"}}""", HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = openAIHttpClient(key, object : HttpClientEngineBase("test") {
            override val config = engine.config
            override val supportedCapabilities = engine.supportedCapabilities + SSECapability
            override suspend fun execute(data: HttpRequestData): HttpResponseData = engine.execute(data)
            override fun close() { super.close(); engine.close() }
        })
        val provider = OpenAIProvider(key, http = http)
        try {
            assertTrue(provider.availability("gpt-test").actionable)
            assertEquals(listOf("gpt-test"), provider.models().map { it.id })
            val model = provider.model(AgentConfig(provider = "openai", model = "gpt-test"), ModelInfo("gpt-test", reasoning = emptyList(), tools = true))
            assertSame(provider.promptExecutor, model.executor)
            assertSame(provider.promptExecutor, provider.model(AgentConfig(provider = "openai", model = "gpt-test")).executor)
            val error = assertFailsWith<IllegalStateException> {
                model.request("Stable instructions", listOf(SessionEntry.UserMessage("hello")), listOf(ToolDescriptor("z", "Z"), ToolDescriptor("a", "A"))).toList()
            }
            assertTrue(error.message.orEmpty().contains("API key"))
            val request = assertNotNull(sent)
            assertEquals("Stable instructions", request["instructions"]?.jsonPrimitive?.content)
            assertEquals(false, request["store"]?.jsonPrimitive?.boolean)
            assertNull(request["reasoning"])
            assertTrue(request.getValue("tools").jsonArray.all { it.jsonObject["strict"]?.jsonPrimitive?.boolean == false })
            assertEquals(listOf("a", "z"), request.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        } finally { provider.close() }
    }

    @Test
    fun missingKeyHasAnActionableSetupReason() = runTest {
        val provider = OpenAIProvider({ null })
        try {
            val state = provider.availability("custom")
            assertFalse(state.actionable)
            assertEquals("needs_api_key", state.reasonCode)
        } finally { provider.close() }
    }
}
