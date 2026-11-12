package kinuvo.server.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class WebFetchToolTest {
    @Test fun truncationKeepsWholeCharacters() = runTest {
        val tool = WebFetchTool(HttpClient(MockEngine { respond("ab𐌲𐌿") }), maxChars = 3)
        assertEquals("HTTP 200\n\nab\n…[truncated]", tool.execute(buildJsonObject { put("url", "https://example.com") }))
    }
}
