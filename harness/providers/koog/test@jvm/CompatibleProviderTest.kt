package kinuvo.harness.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.flow.toList
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.http.content.OutgoingContent
import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CompatibleProviderTest {
    @Test
    fun compatibleEndpointRetainsPathAndModelIdsAndUsesChatCompletions() = runTest {
        val home = Files.createTempDirectory("koog-provider").toFile()
        val files = AgentFiles(Path(home.path))
        var token = "first-key"
        var sent: JsonObject? = null
        val engine = MockEngine { request ->
            assertEquals("localhost", request.url.host)
            assertEquals(1234, request.url.port)
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/custom/v1/models" -> respond("""{"data":[{"id":"vendor/local-model"},{"id":"another-model"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "/custom/v1/chat/completions" -> {
                    sent = Json.parseToJsonElement(request.body.text()).jsonObject
                    respond("""{"id":"test","object":"chat.completion","created":0,"model":"vendor/local-model","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected path: ${request.url}")
            }
        }
        val key = StoredProviderKey("test", "Test", "TEST_KEY", files, Secrets(), PrivateFile(Path(home.path, "key")), PrivateFile(Path(home.path, "ref")), { mapOf("TEST_KEY" to token) })
        val provider = CompatibleProvider("local", ModelProviderConfig(baseUrl = "http://localhost:1234/custom/v1"), files, Secrets(), null, engine, key)
        try {
            assertEquals(listOf("another-model", "vendor/local-model"), provider.models().map { it.id })
            token = "rotated-key"
            assertTrue(provider.availability("vendor/local-model").actionable)
            val model = provider.model(AgentConfig(provider = "local", model = "vendor/local-model"), ModelInfo("vendor/local-model", tools = true, reasoning = emptyList()))
            assertTrue(model.model.supports(LLMCapability.OpenAIEndpoint.Completions))
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""").jsonObject
            val history = listOf(SessionEntry.Reasoning("old summary", encrypted = "old-openai-state"), SessionEntry.UserMessage("hello"))
            val response = model.executor.execute(SessionMessages(model).prompt("instructions", history), model.model, listOf(RawSchemaToolDescriptor("echo", "Echo", schema)))
            assertEquals("hello", response.parts.filterIsInstance<MessagePart.Text>().single().text)
            val request = assertNotNull(sent)
            assertEquals("vendor/local-model", request.getValue("model").jsonPrimitive.content)
            assertEquals(schema, request.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("parameters"))
            assertNull(request["reasoning"])
            assertNull(request["store"])
            assertEquals(listOf("system", "user"), request.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertFalse(request.toString().contains("old-openai-state"))
            val continuation = SessionMessages(model).prompt("", parallelToolHistory())
            model.executor.execute(continuation, model.model)
            assertParallelToolMessages(assertNotNull(sent))
        } finally { provider.close(); home.deleteRecursively() }
    }

    @Test
    fun compatibleEndpointStreamsWithoutAuthentication() = runTest {
        val home = Files.createTempDirectory("koog-stream").toFile()
        val files = AgentFiles(Path(home.path))
        var sent: JsonObject? = null
        var authorization: String? = null
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst(HttpHeaders.Authorization)
            sent = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val response = """data: {"id":"test","object":"chat.completion.chunk","created":0,"model":"local","choices":[{"index":0,"delta":{"role":"assistant","content":"hello"},"finish_reason":null}]}

 data: {"id":"test","object":"chat.completion.chunk","created":0,"model":"local","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

 data: [DONE]

""".replace("\n data:", "\ndata:").toByteArray()
            exchange.responseHeaders.set(HttpHeaders.ContentType, "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val key = StoredProviderKey("test", "Test", "TEST_KEY", files, Secrets(), PrivateFile(Path(home.path, "key")), PrivateFile(Path(home.path, "ref")), { emptyMap() })
        val provider = CompatibleProvider("local", ModelProviderConfig(baseUrl = "http://127.0.0.1:${server.address.port}/v1/", apiKeyRequired = false), files, Secrets(), null, key = key)
        try {
            assertTrue(provider.availability("local").actionable)
            val model = provider.model(AgentConfig(provider = "local", model = "local"), ModelInfo("local", tools = true, reasoning = emptyList()))
            val frames = model.executor.executeStreaming(prompt("test") { user("hello") }, model.model).toList()
            assertEquals("hello", frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text })
            assertTrue(frames.any { it is StreamFrame.End })
            assertNull(authorization)
            assertTrue(assertNotNull(sent).getValue("stream").jsonPrimitive.boolean)
            val continuation = SessionMessages(model).prompt("", parallelToolHistory())
            model.executor.executeStreaming(continuation, model.model).toList()
            assertParallelToolMessages(assertNotNull(sent))
        } finally { provider.close(); server.stop(0); home.deleteRecursively() }
    }

    @Test
    fun endpointConfigurationRoundTripsAndSeparatesCustomProviders() = runTest {
        val home = Files.createTempDirectory("koog-config").toFile()
        val files = AgentFiles(Path(home.path))
        val options = ModelProviderConfig(baseUrl = "http://localhost:1234/v1/", models = listOf(ModelInfo("local", tools = true, reasoning = emptyList())), apiKeyRequired = false)
        try {
            files.updateConfig { it.copy(providers = mapOf("local" to options)) }
            assertEquals(options, files.config().providers["local"])
            val providers = compatibleProviders(files)
            try {
                assertEquals(listOf("local"), providers.flatMap { it.modelProviders }.map { it.id })
                val local = providers.last().modelProviders.single()
                assertEquals(options.models, local.models())
                assertTrue(local.availability("local").actionable)
            } finally { providers.forEach { it.close() } }
        } finally { home.deleteRecursively() }
    }

    @Test
    fun validatesEndpoint() {
        assertEquals("http://localhost:1234/v1/", endpoint("http://localhost:1234/v1"))
        for (url in listOf("", "file:///tmp/model", "https://key@example.com", "https://example.com?key=secret")) assertFailsWith<IllegalArgumentException> { endpoint(url) }
    }
}

private fun OutgoingContent.text(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ContentWrapper -> delegate().text()
    else -> error("Unexpected request content")
}

private fun parallelToolHistory() = listOf(
    SessionEntry.UserMessage("Read both files"),
    SessionEntry.ToolCall("first", "read", "{}"),
    SessionEntry.ToolCall("second", "read", "{}"),
    SessionEntry.ToolResult("first", "read", "one"),
    SessionEntry.ToolResult("second", "read", "two"),
)

private fun assertParallelToolMessages(request: JsonObject) {
    val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
    assertEquals(listOf("user", "assistant", "tool", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
    assertEquals(listOf("first", "second"), messages[1].getValue("tool_calls").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    assertEquals(listOf("first", "second"), messages.drop(2).map { it.getValue("tool_call_id").jsonPrimitive.content })
}
