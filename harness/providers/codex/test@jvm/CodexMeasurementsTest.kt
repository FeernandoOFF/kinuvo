package kinuvo.harness.codex

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.streaming.StreamFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.Model
import kinuvo.harness.core.TokenUsageCapture
import kinuvo.harness.core.measured
import kinuvo.protocol.RequestKind
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
        var metrics: RequestMetrics? = null
        try {
            val frames = OpenAIModel(AgentConfig(), client).measured(
                AgentConfig(), RequestKind.Chat, "Help the user", listOf(SessionEntry.UserMessage("Hi")), emptyList(),
            ) { metrics = it }.toList()
            assertEquals("Hello", frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text })
            assertEquals(1, frames.filterIsInstance<StreamFrame.End>().size)
            assertTrue(assertNotNull(metrics).success)
            assertEquals(4, metrics.cachedTokens)
        } finally { client.close(); http.close() }
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
            var result: RequestMetrics? = null
            val model = Model { _, _, _ -> flow {
                transport.sse("responses", "$cached", String::class, { true }, { it }, { it }, emptyMap(), emptyMap()).collect {
                    emit(StreamFrame.TextDelta("done"))
                }
                emit(StreamFrame.End())
            } }
            model.measured(AgentConfig(), RequestKind.Chat, "", emptyList(), emptyList()) { result = it }.toList()
            return assertNotNull(result)
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
}
