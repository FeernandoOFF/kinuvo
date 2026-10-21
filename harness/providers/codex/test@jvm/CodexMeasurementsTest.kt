package kinuvo.harness.codex

import kinuvo.harness.testing.*

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.streaming.StreamFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.TokenUsageCapture
import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.AgentRun
import kinuvo.harness.core.store.InMemoryStore
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RequestMetrics
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.reflect.KClass
import kotlin.test.*

class CodexMeasurementsTest {
    @Test
    fun metadataDoesNotInterruptResponseStreaming() = runTest {
        val events = listOf(
            """{"type":"response.metadata","metadata":{"request_id":"test"}}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hello","sequence_number":1}""",
            """{"type":"response.metadata","metadata":{}}""",
            """{"type":"response.completed","sequence_number":2,"response":{"id":"resp_1","created_at":0,"model":"test","status":"completed","output":[],"parallel_tool_calls":true,"text":{},"usage":{"input_tokens":10,"output_tokens":1,"total_tokens":11,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":0}}}}""",
        )
        val http = HttpClient(MockEngine { error("No network expected") })
        val transport = object : KoogHttpClient by KoogHttpClient.fromKtorClient("test", KotlinLogging.logger("test"), http) {
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
                dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
                parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
                events.filter(dataFilter).forEach { data ->
                    processStreamingChunk(decodeStreamingResponse(data))?.let { emit(it) }
                }
            }
        }
        val client = OpenAILLMClient(
            OpenAIClientSettings(baseUrl = CODEX_BASE_URL, responsesAPIPath = "responses"),
            httpClient = CodexMeasurements(transport),
        )
        try {
            val (events, metrics) = openAIModel(AgentConfig(), client).measure()
            assertEquals("Hello", events.filterIsInstance<AgentEvent.TextMessageEnd>().single().text)
            assertTrue(metrics.success)
            assertEquals(4, metrics.cachedTokens)
        } finally { client.close(); http.close() }
    }

    @Test
    fun completeCodexRequestsUseSseForCompressionNodes() = runTest {
        val http = HttpClient(MockEngine { error("Codex must use SSE") })
        var streamed = false
        val transport = object : KoogHttpClient by KoogHttpClient.fromKtorClient("test", KotlinLogging.logger("test"), http) {
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
                dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
                parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
                streamed = true
                val events = listOf(
                    """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Summary","sequence_number":1}""",
                    """{"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"Summary","sequence_number":2}""",
                    """{"type":"response.completed","sequence_number":3,"response":{"id":"resp_1","created_at":0,"model":"test","status":"completed","output":[],"parallel_tool_calls":true,"text":{},"usage":{"input_tokens":10,"output_tokens":1,"total_tokens":11,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}}""",
                )
                events.filter(dataFilter).forEach { data -> processStreamingChunk(decodeStreamingResponse(data))?.let { emit(it) } }
            }
        }
        val configured = openAIModel(AgentConfig(), ResponsesLLMClient(OpenAIClientSettings(baseUrl = CODEX_BASE_URL, responsesAPIPath = "responses"), transport))
        try {
            val response = configured.executor.execute(kinuvo.harness.core.SessionMessages(configured).prompt("Summarize", listOf(SessionEntry.UserMessage("history"))), configured.model)
            assertTrue(streamed)
            assertEquals("Summary", response.textContent())
            assertEquals(10, response.metaInfo.inputTokensCount)
        } finally { configured.executor.close(); http.close() }
    }

    @Test
    fun usageDistinguishesUnknownFromZeroAndHandlesMalformedEvents() {
        val capture = TokenUsageCapture()
        capture.read("not json")
        capture.read("""{"type":{}}""")
        assertNull(capture.cached)
        capture.read("""{"type":"response.completed","response":{"usage":{"input_tokens":2000,"output_tokens":150,"input_tokens_details":{"cached_tokens":0}}}}""")
        assertEquals(2000, capture.input)
        assertEquals(150, capture.output)
        assertEquals(0, capture.cached)
        assertNull(capture.cacheWrite)
        assertEquals(true, capture.completed)
        capture.read("""{"type":"response.incomplete","response":{"usage":{"input_tokens":3000,"output_tokens":200}}}""")
        assertEquals(false, capture.completed)
        assertNull(capture.cached)
    }

    @Test
    fun streamingUsageIsRequestLocalEvenWhenRequestsOverlap() = runTest {
        val http = HttpClient(MockEngine { error("No network expected") })
        val base = KoogHttpClient.fromKtorClient("test", KotlinLogging.logger("test"), http)
        val transport = CodexMeasurements(object : KoogHttpClient by base {
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
                dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
                parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
                yield()
                val event = """{"type":"response.completed","response":{"usage":{"input_tokens":2000,"output_tokens":100,"input_tokens_details":{"cached_tokens":$requestBody}}}}"""
                processStreamingChunk(decodeStreamingResponse(event))?.let { emit(it) }
            }
        })
        suspend fun measure(cached: Int): RequestMetrics {
            val model = scriptedModel { _, _ -> flow {
                transport.sse("responses", "$cached", String::class, { true }, { it }, { it }, emptyMap(), emptyMap()).collect {
                    emit(StreamFrame.TextDelta("done"))
                }
                emit(StreamFrame.End())
            } }
            return model.measure().second
        }
        try {
            val zero = async { measure(0) }
            val hit = async { measure(1024) }
            assertEquals(0, zero.await().cachedTokens)
            val result = hit.await()
            assertEquals(1024, result.cachedTokens)
            assertEquals(2000, result.inputTokens)
            assertEquals(100, result.outputTokens)
            assertNotNull(result.firstTokenMs)
            assertTrue(result.success)
        } finally { http.close() }
    }

    private suspend fun AgentModel.measure(): Pair<List<AgentEvent>, RequestMetrics> {
        val store = InMemoryStore()
        val agent = Agent("test", "Test", AgentConfig())
        val session = store.createSession(agent, "Help the user")
        val events = AgentRun(agent, store, this, emptyList()).run(session.id, "Hi").toList()
        return events to store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>().single().metrics
    }
}
