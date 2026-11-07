package kinuvo.cli

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpBridgeTest {
    private fun run(input: String, post: (String) -> String?): List<String> {
        val output = Collections.synchronizedList(mutableListOf<String>())
        runBlocking { bridge(input.reader().buffered(), { output += it }, post) }
        return output
    }

    @Test fun forwardsEachMessageAndWritesRepliesAsSingleLines() {
        val out = run("{\"id\":1,\"method\":\"ping\"}\n\n{\"method\":\"notifications/initialized\"}\n") { body ->
            if ("\"id\"" in body) "{\"id\":1,\n\"result\":{}}" else null
        }
        assertEquals(listOf("{\"id\":1, \"result\":{}}"), out)
    }

    @Test fun unreachableServerBecomesAnErrorForRequestsOnly() {
        val out = run("{\"id\":7,\"method\":\"tools/list\"}\n{\"method\":\"notifications/initialized\"}\n") { throw IOException("down") }
        assertEquals(1, out.size)
        assertTrue("\"id\":7" in out.single() && "down" in out.single())
    }
}
