package kinuvo.harness.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.flow.toList
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.request.get
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

class KoogProviderTest {
    @Test
    fun googleToolContinuationRetainsEachSignatureAtTheWireBoundary() = runTest {
        val home = Files.createTempDirectory("koog-google-signatures").toFile()
        val files = AgentFiles(Path(home.path))
        var requests = 0
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith(":generateContent"))
            val sent = Json.parseToJsonElement(request.body.text()).jsonObject
            val response = if (requests++ == 0)
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"first","name":"read","args":{}},"thoughtSignature":"sig1"},{"functionCall":{"id":"second","name":"read","args":{}},"thoughtSignature":"sig2"}]},"finishReason":"STOP","index":0}]}"""
            else {
                val parts = sent.getValue("contents").jsonArray.first { it.jsonObject["role"]?.jsonPrimitive?.content == "model" }.jsonObject.getValue("parts").jsonArray
                assertEquals(listOf("sig1", "sig2"), parts.map { (it.jsonObject["thoughtSignature"] ?: it.jsonObject.getValue("thought_signature")).jsonPrimitive.content })
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"done"}]},"finishReason":"STOP","index":0}]}"""
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val key = StoredProviderKey("test", "Test", "TEST_KEY", files, Secrets(), PrivateFile(Path(home.path, "key")), PrivateFile(Path(home.path, "ref")), { mapOf("TEST_KEY" to "test-key") })
        val provider = KoogProvider("google", "Google", providerKinds.first { it.id == "google" }, ModelProviderConfig(), files, Secrets(), null, engine, key)
        try {
            val model = provider.model(AgentConfig(provider = "google", model = "test"), ModelInfo("test", tools = true, reasoning = emptyList()))
            val frames = model.executor.executeStreaming(prompt("test") { user("go") }, model.model).toList()
            val history = frames.mapNotNull { when (it) {
                is StreamFrame.ReasoningComplete -> SessionEntry.Reasoning("", encrypted = it.encrypted, format = "google")
                is StreamFrame.ToolCallComplete -> SessionEntry.ToolCall(it.id!!, it.name, it.content)
                else -> null
            } } + listOf(SessionEntry.ToolResult("first", "read", "ok"), SessionEntry.ToolResult("second", "read", "ok"))
            assertEquals(listOf("sig1", "sig2"), history.filterIsInstance<SessionEntry.Reasoning>().map { it.encrypted })
            val continuation = SessionMessages(model).prompt("", listOf(SessionEntry.UserMessage("go")) + history)
            val result = model.executor.executeStreaming(continuation, model.model).toList()
            assertEquals("done", result.filterIsInstance<StreamFrame.TextDelta>().single().text)
            assertEquals(2, requests)
        } finally { provider.close(); home.deleteRecursively() }
    }

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
        val provider = KoogProvider("local", "Local", providerKinds.last(), ModelProviderConfig(baseUrl = "http://localhost:1234/custom/v1"), files, Secrets(), null, engine, key)
        try {
            assertEquals(listOf("another-model", "vendor/local-model"), provider.models().map { it.id })
            token = "rotated-key"
            assertTrue(provider.availability("vendor/local-model").actionable)
            val model = provider.model(AgentConfig(provider = "local", model = "vendor/local-model"), ModelInfo("vendor/local-model", tools = true, reasoning = emptyList()))
            assertTrue(model.model.supports(LLMCapability.OpenAIEndpoint.Completions))
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""").jsonObject
            val response = model.executor.execute(prompt("test") { system("instructions"); user("hello") }, model.model, listOf(RawSchemaToolDescriptor("echo", "Echo", schema)))
            assertEquals("hello", response.parts.filterIsInstance<MessagePart.Text>().single().text)
            val request = assertNotNull(sent)
            assertEquals("vendor/local-model", request.getValue("model").jsonPrimitive.content)
            assertEquals(schema, request.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("parameters"))
            assertNull(request["reasoning"])
            assertNull(request["store"])
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
        val provider = KoogProvider("local", "Local", providerKinds.last(), ModelProviderConfig(baseUrl = "http://127.0.0.1:${server.address.port}/v1/", apiKeyRequired = false), files, Secrets(), null, key = key)
        try {
            assertTrue(provider.availability("local").actionable)
            val model = provider.model(AgentConfig(provider = "local", model = "local"))
            val frames = model.executor.executeStreaming(prompt("test") { user("hello") }, model.model).toList()
            assertEquals("hello", frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text })
            assertTrue(frames.any { it is StreamFrame.End })
            assertNull(authorization)
            assertTrue(assertNotNull(sent).getValue("stream").jsonPrimitive.boolean)
        } finally { provider.close(); server.stop(0); home.deleteRecursively() }
    }

    @Test
    fun endpointConfigurationRoundTripsAndSeparatesCustomProviders() = runTest {
        val home = Files.createTempDirectory("koog-config").toFile()
        val files = AgentFiles(Path(home.path))
        val options = ModelProviderConfig(type = "openai-compatible", baseUrl = "http://localhost:1234/v1/", models = listOf(ModelInfo("local", tools = true, reasoning = emptyList())), apiKeyRequired = false)
        try {
            files.updateConfig { it.copy(providers = mapOf("local" to options)) }
            assertEquals(options, files.config().providers["local"])
            val providers = koogProviders(files)
            try {
                assertEquals(listOf("anthropic", "google", "deepseek", "openrouter", "mistral", "alibaba", "ollama", "local"), providers.flatMap { it.modelProviders }.map { it.id })
                val local = providers.last().modelProviders.single()
                assertEquals(options.models, local.models())
                assertTrue(local.availability("local").actionable)
            } finally { providers.forEach { it.close() } }
        } finally { home.deleteRecursively() }
    }

    @Test
    fun nativeClientsPreserveMcpSchemasInTheirWireFormats() = runTest {
        val home = Files.createTempDirectory("koog-native").toFile()
        val files = AgentFiles(Path(home.path))
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""").jsonObject
        try {
            for (kind in providerKinds.filter { it.id in listOf("anthropic", "google") }) {
                var sent: JsonObject? = null
                val engine = MockEngine { request ->
                    sent = Json.parseToJsonElement(request.body.text()).jsonObject
                    val response = if (kind.id == "anthropic") """{"id":"test","type":"message","role":"assistant","model":"test","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":4,"output_tokens":2}}"""
                        else """{"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2,"totalTokenCount":6}}"""
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val key = StoredProviderKey("test", "Test", "TEST_KEY", files, Secrets(), PrivateFile(Path(home.path, "key")), PrivateFile(Path(home.path, "ref")), { mapOf("TEST_KEY" to "test-key") })
                val provider = KoogProvider(kind.id, kind.name, kind, ModelProviderConfig(), files, Secrets(), null, engine, key)
                try {
                    val model = provider.model(AgentConfig(provider = kind.id, model = "test"), ModelInfo("test", tools = true, reasoning = emptyList()))
                    val response = model.executor.execute(prompt("test") { user("hello") }, model.model, listOf(RawSchemaToolDescriptor("echo", "Echo", schema)))
                    assertEquals("hello", response.parts.filterIsInstance<MessagePart.Text>().single().text)
                    val tools = assertNotNull(sent).getValue("tools").jsonArray
                    val parameters = if (kind.id == "anthropic") tools.single().jsonObject.getValue("input_schema") else {
                        val group = tools.single().jsonObject
                        (group["functionDeclarations"] ?: group.getValue("function_declarations")).jsonArray.single().jsonObject.getValue("parameters")
                    }
                    assertEquals(schema, parameters, kind.id)
                } finally { provider.close() }
            }
        } finally { home.deleteRecursively() }
    }

    @Test
    fun authenticationUsesProviderHeadersAndAllowsKeylessLocalEndpoints() = runTest {
        for (kind in listOf("anthropic", "google", "openai-compatible", "ollama")) {
            val engine = MockEngine { request ->
                when (kind) {
                    "anthropic" -> { assertEquals("key", request.headers["x-api-key"]); assertEquals("2023-06-01", request.headers["anthropic-version"]); assertNull(request.headers[HttpHeaders.Authorization]) }
                    "google" -> { assertEquals("key", request.headers["x-goog-api-key"]); assertNull(request.headers[HttpHeaders.Authorization]) }
                    "ollama" -> assertNull(request.headers[HttpHeaders.Authorization])
                    else -> assertEquals("Bearer key", request.headers[HttpHeaders.Authorization])
                }
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val http = providerHttpClient("http://localhost:1234/", kind, { if (kind == "ollama") null else "key" }, kind != "ollama", engine)
            try { http.get("models") } finally { http.close() }
        }
    }

    @Test
    fun validatesEndpointAndPreservesUnknownCapabilities() {
        assertEquals("http://localhost:1234/v1/", endpoint("http://localhost:1234/v1"))
        for (url in listOf("", "file:///tmp/model", "https://key@example.com", "https://example.com?key=secret")) assertFailsWith<IllegalArgumentException> { endpoint(url) }
        val info = ai.koog.prompt.llm.LLModel(id = "unknown", provider = ai.koog.prompt.llm.LLMProvider.OpenAI).info()
        assertNull(info.tools)
        assertNull(info.input)
    }

    @Test
    fun nativeSignedReasoningReplaysContentWithItsSignature() {
        val model = kinuvo.harness.testing.scriptedModel { _, _ -> kotlinx.coroutines.flow.emptyFlow() }.copy(reasoningInContent = true, reasoningFormat = "anthropic")
        val message = SessionMessages(model).messages(listOf(SessionEntry.Reasoning("thought", encrypted = "signature", format = "anthropic"))).single()
        val reasoning = message.parts.single() as MessagePart.Reasoning
        assertEquals(listOf("thought"), reasoning.content)
        assertEquals("signature", reasoning.encrypted)
        assertNull(reasoning.summary)
    }
}

private fun OutgoingContent.text(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ContentWrapper -> delegate().text()
    else -> error("Unexpected request content")
}
