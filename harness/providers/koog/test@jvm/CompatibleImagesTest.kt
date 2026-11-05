package kinuvo.harness.koog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kinuvo.harness.core.plugin.GeneratedImage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CompatibleImagesTest {
    @Test fun sendsModalitiesAndReferenceImages() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(listOf("image", "text"), body.getValue("modalities").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(body.toString().contains("data:image/png;base64,AQ=="))
            respond("""{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,AQID"}}]}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { defaultRequest { url("https://example.test/v1/") } }
        try {
            assertContentEquals(byteArrayOf(1, 2, 3), CompatibleImages(http).generate("gemini-image", "Edit", listOf(GeneratedImage(byteArrayOf(1))), null).single().bytes)
        } finally { http.close() }
    }

    @Test fun rejectsRemoteUrlsAndEmptyResults() = runTest {
        for (images in listOf("[]", """[{"image_url":{"url":"https://example.test/image.png"}}]""")) {
            val http = HttpClient(MockEngine { respond("""{"choices":[{"message":{"images":$images}}]}""") }) { defaultRequest { url("https://example.test/") } }
            try { assertFails { CompatibleImages(http).generate("image", "Draw", emptyList(), null) } } finally { http.close() }
        }
    }
}
