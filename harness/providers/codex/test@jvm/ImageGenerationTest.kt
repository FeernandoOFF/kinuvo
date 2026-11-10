package kinuvo.harness.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kinuvo.server.plugin.GeneratedImage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ImageGenerationTest {
    @Test fun catalogsExposeImageOutput() {
        assertEquals(listOf("image"), parseOpenAIModels("""{"data":[{"id":"gpt-image-1"}]}""").single().output)
        assertEquals(listOf("text", "image"), parseCodexModels("""{"models":[{"slug":"gpt-5.4"}]}""").single().output)
        assertEquals(listOf("image"), parseCodexModels("""{"models":[{"slug":"future","output_modalities":["image"]}]}""").single().output)
    }

    @Test fun imagesApiGeneratesAndEdits() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (requests++ == 0) {
                assertEquals("/v1/images/generations", request.url.encodedPath)
                assertEquals("gpt-image-1", Json.parseToJsonElement((request.body as TextContent).text).jsonObject["model"]?.jsonPrimitive?.content)
            } else {
                assertEquals("/v1/images/edits", request.url.encodedPath)
                assertEquals("multipart", request.body.contentType?.contentType)
            }
            respond("""{"data":[{"b64_json":"AQID"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { defaultRequest { url(OPENAI_BASE_URL) } }
        try {
            val images = OpenAIImages(http)
            assertContentEquals(byteArrayOf(1, 2, 3), images.generate("gpt-image-1", "Draw", emptyList(), "1024x1024").single().bytes)
            assertEquals(1, images.generate("gpt-image-1", "Edit", listOf(GeneratedImage(byteArrayOf(1))), null).size)
            assertEquals(2, requests)
        } finally { http.close() }
    }

    @Test fun codexReadsCompletedImageOutputAndSendsReferences() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
            assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
            assertTrue(body.toString().contains("data:image/png;base64,AQ=="))
            assertEquals("image_generation", body.getValue("tools").jsonArray.single().jsonObject["type"]?.jsonPrimitive?.content)
            respond("data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"AQID\"}]}}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { defaultRequest { url(CODEX_BASE_URL) } }
        try {
            assertContentEquals(byteArrayOf(1, 2, 3), CodexImages(http).generate("gpt-test", "Edit", listOf(GeneratedImage(byteArrayOf(1))), null).single().bytes)
        } finally { http.close() }
    }

    @Test fun codexRetainsOutputItemsWhenCompletionOmitsThem() = runTest {
        val item = """{"id":"image","type":"image_generation_call","result":"AQID"}"""
        val http = HttpClient(MockEngine {
            respond("data: {\"type\":\"response.output_item.done\",\"item\":$item}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n")
        }) { defaultRequest { url(CODEX_BASE_URL) } }
        try { assertEquals(1, CodexImages(http).generate("gpt-test", "Draw", emptyList(), null).size) }
        finally { http.close() }
    }

    @Test fun codexRejectsTruncatedAndFailedStreams() = runTest {
        for (event in listOf("response.failed", "response.output_item.done")) {
            val http = HttpClient(MockEngine { respond("data: {\"type\":\"$event\"}\n\n") }) { defaultRequest { url(CODEX_BASE_URL) } }
            try { assertFailsWith<IllegalStateException> { CodexImages(http).generate("gpt-test", "Draw", emptyList(), null) } }
            finally { http.close() }
        }
    }
}
