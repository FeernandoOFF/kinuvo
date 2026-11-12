package kinuvo.server

import kinuvo.server.tools.ShowHtmlTool
import kinuvo.protocol.approval.Risk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ShowHtmlToolTest {
    private val tool = ShowHtmlTool()
    @Test fun acceptsDocumentsAndFragments() = runTest {
        for (html in listOf("<p>Hello</p>", "<!doctype html><html><body>Hello</body></html>")) {
            assertEquals("Shown to the user.", tool.execute(buildJsonObject { put("html", html); put("data", buildJsonObject { put("x", 1) }) }))
        }
        assertEquals(Risk.Low, tool.risk)
    }
    @Test fun rejectsInvalidOrOversizedArguments() = runTest {
        for (args in listOf("{}", """{"html":"  "}""", """{"html":5}""", """{"html":"Hi","data":[]}""", """{"html":"Hi","title":4}""")) {
            assertFailsWith<IllegalArgumentException> { tool.execute(Json.parseToJsonElement(args).jsonObject) }
        }
        assertFailsWith<IllegalArgumentException> { tool.execute(buildJsonObject { put("html", "é".repeat(300000)) }) }
    }
}
