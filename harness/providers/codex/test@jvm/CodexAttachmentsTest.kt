package kinuvo.harness.codex

import kinuvo.harness.testing.*
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.KClass
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class CodexAttachmentsTest {
    @Test
    fun sendsImageAndFileBytesAlongsideTextOnEveryReplay() = runTest {
        var request: JsonObject? = null
        val http = codexHttpClient({ CodexTokens("test", "acct") }, kotlinx.coroutines.flow.MutableStateFlow(null), MockEngine {
            respond("""{"error":{"message":"test response"}}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val transport = object : KoogHttpClient by KoogHttpClient.fromKtorClient("test", KotlinLogging.logger("test"), http) {
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> {
                request = Json.parseToJsonElement(requestBody as String).jsonObject
                return emptyFlow()
            }
        }
        val client = OpenAILLMClient(OpenAIClientSettings(baseUrl = CODEX_BASE_URL, responsesAPIPath = "responses"), httpClient = transport)
        val image = Attachment("image", "photo.png", "image/png", 3)
        val file = Attachment("file", "journal.pdf", "application/pdf", 3)
        val model = openAIModel(AgentConfig(), client, ModelInfo("test", input = listOf("text", "image", "file"))) { byteArrayOf(1, 2, 3) }
        val history = listOf(SessionEntry.UserMessage("What do these show?", attachments = listOf(image, file)))
        try {
            assertFailsWith<IncompleteStreamException> { model.request("Help the user", history, emptyList()).toList() }
            val parts = assertNotNull(request).getValue("input").jsonArray.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }.map { it.jsonObject }
            assertEquals("What do these show?", parts.first { it["type"]?.jsonPrimitive?.content == "input_text" }["text"]?.jsonPrimitive?.content)
            val imagePart = parts.first { it["type"]?.jsonPrimitive?.content == "input_image" }
            assertEquals("data:image/png;base64,${Base64.encode(byteArrayOf(1, 2, 3))}", imagePart["image_url"]?.jsonPrimitive?.content)
            val filePart = parts.first { it["type"]?.jsonPrimitive?.content == "input_file" }
            assertEquals("journal.pdf", filePart["filename"]?.jsonPrimitive?.content)
            assertEquals("data:application/pdf;base64,AQID", filePart["file_data"]?.jsonPrimitive?.content)
            assertFailsWith<IncompleteStreamException> { model.request("Help the user", history + SessionEntry.AssistantMessage("done") + SessionEntry.UserMessage("Look again"), emptyList()).toList() }
            assertTrue(request.toString().contains("input_image"))
            assertTrue(request.toString().contains("input_file"))
        } finally { http.close() }
    }

    @Test
    fun followsToolResultsWithTheirFiles() = runTest {
        var request: JsonObject? = null
        val http = codexHttpClient({ CodexTokens("test", "acct") }, kotlinx.coroutines.flow.MutableStateFlow(null), MockEngine {
            respond("""{"error":{"message":"test response"}}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val transport = object : KoogHttpClient by KoogHttpClient.fromKtorClient("test", KotlinLogging.logger("test"), http) {
            override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> {
                request = Json.parseToJsonElement(requestBody as String).jsonObject
                return emptyFlow()
            }
        }
        val client = OpenAILLMClient(OpenAIClientSettings(baseUrl = CODEX_BASE_URL, responsesAPIPath = "responses"), httpClient = transport)
        val model = openAIModel(AgentConfig(), client, ModelInfo("test", input = listOf("text", "image", "file"))) { byteArrayOf(1, 2, 3) }
        val history = listOf(
            SessionEntry.UserMessage("What's in Ana's photo?"),
            SessionEntry.ToolCall("call-1", "knowledge", "{}"),
            SessionEntry.ToolResult("call-1", "knowledge", "Attached photo.png", attachments = listOf(Attachment("image", "photo.png", "image/png", 3))),
        )
        try {
            assertFailsWith<IncompleteStreamException> { model.request("Help the user", history, emptyList()).toList() }
            val input = assertNotNull(request).getValue("input").jsonArray.map { it.jsonObject }
            assertEquals("function_call_output", input[input.size - 2]["type"]?.jsonPrimitive?.content)
            val parts = input.last().getValue("content").jsonArray.map { it.jsonObject }
            assertEquals("data:image/png;base64,AQID", parts.single { it["type"]?.jsonPrimitive?.content == "input_image" }["image_url"]?.jsonPrimitive?.content)
        } finally { http.close() }
    }
}
