package kinuvo.plugins.browser

import java.io.File
import java.net.ServerSocket
import dev.kdriver.cdp.CDPException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.browser.*
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class BrowserRecoveryTest {
    private val directories = TemporaryDirectories()

    @AfterTest fun cleanup() = directories.close()

    @Test fun staleInterceptionDoesNotStopLaterRequests(): Unit = runBlocking {
        finishBrowserRequest { throw CDPException("Fetch.continueRequest", -32602, "Invalid InterceptionId.", null) }
        finishBrowserRequest { throw CDPException("Fetch.failRequest", -32602, "Invalid InterceptionId.", null) }
        var continued = false
        finishBrowserRequest { continued = true }
        assertTrue(continued)
        assertFailsWith<CDPException> {
            finishBrowserRequest { throw CDPException("Fetch.continueRequest", -32602, "Invalid parameters", null) }
        }
        assertFailsWith<CancellationException> { finishBrowserRequest { throw CancellationException() } }
    }

    @Test fun failedExplicitNavigationIncludesChromeError() = runBlocking {
        val runtime = BrowserRuntime(directories.create("browser-error-").toFile())
        val owner = BrowserOwner(AgentId("error"))
        val port = ServerSocket(0).use { it.localPort }
        try {
            val error = assertFailsWith<IllegalStateException> {
                runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "http://127.0.0.1:$port"))
            }
            assertContains(error.message.orEmpty(), "net::ERR_CONNECTION_REFUSED")
            assertTrue(runtime.tabs(owner).isEmpty())
            assertEquals("about:blank", runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "about:blank"))!!.tab.url)
        } finally { runtime.close() }
    }

    @Test fun blankTabOpensAndLists() = runBlocking {
        val runtime = BrowserRuntime(directories.create("browser-blank-").toFile())
        val owner = BrowserOwner(AgentId("blank"))
        try {
            withTimeout(45_000) {
                val opened = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "about:blank"))!!
                assertEquals("about:blank", opened.tab.url)
                assertEquals(listOf(opened.tab), runtime.tabs(owner))
            }
        } finally { runtime.close() }
    }

    @Test fun failedSavedNavigationDoesNotBlockListingOrOpening() = runBlocking {
        val home = directories.create("browser-recovery-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        File(home, "kdriver-tabs.json").writeText("""[{"id":"offline","owner":{"agentId":"recovery"},"url":"http://127.0.0.1:$port","control":{}}]""")
        var runtime = BrowserRuntime(home)
        val owner = BrowserOwner(AgentId("recovery"))
        try {
            withTimeout(45_000) {
                val recovered = runtime.tabs(owner).single()
                assertEquals("offline", recovered.id)
                assertEquals("http://127.0.0.1:$port", recovered.url.trimEnd('/'))
                val opened = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "about:blank"))!!
                assertEquals("about:blank", opened.tab.url)
                assertEquals(2, runtime.tabs(owner).size)
                runtime.close()
                val saved = Json.parseToJsonElement(File(home, "kdriver-tabs.json").readText()).jsonArray
                assertEquals("http://127.0.0.1:$port", saved.first().jsonObject["url"]!!.jsonPrimitive.content.trimEnd('/'))
                runtime = BrowserRuntime(home)
                assertEquals(2, runtime.tabs(owner).size)
            }
        } finally { runtime.close() }
    }
}
