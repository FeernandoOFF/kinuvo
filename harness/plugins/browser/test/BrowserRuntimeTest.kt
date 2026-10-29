package kinuvo.harness.browser

import com.sun.net.httpserver.HttpServer
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class BrowserRuntimeTest {
    @Test fun navigationAllowsPrivateHttpButRejectsFilesAndCustomSchemes() {
        listOf("http://127.0.0.1:8080", "http://192.168.1.1", "https://lab.internal", "about:blank").forEach { assertTrue(BrowserRuntime.allowed(it), it) }
        listOf("file:///etc/passwd", "javascript:alert(1)", "data:text/html,hi", "chrome://settings", "https://user:password@host.test", "https:foo").forEach { assertFalse(BrowserRuntime.allowed(it), it) }
    }

    @Test fun managedChromiumOwnershipReferencesTakeoverPersistenceAndLifecycle() = runBlocking {
        val home = File(System.getenv("KINUVO_HOME") ?: System.getProperty("java.io.tmpdir"), "browser-runtime-proof")
        val runtime = BrowserRuntime(home)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val checking = exchange.requestURI.path == "/check"
            val html = if (checking) """<script>document.title = localStorage.getItem('proof') === 'saved' && document.cookie.includes('login=persistent') ? 'Login persisted' : 'Isolated';</script>""".toByteArray() else """<html><head><title>Browser proof</title></head><body>
                <button onclick="document.title='Clicked';this.textContent='Done'">Submit</button>
                <input aria-label="Message"><button onclick="setTimeout(() => document.querySelector('input').value = 'changed', 100)">Change value</button><input type="password" aria-label="Password">
                <script>document.cookie='login=persistent; max-age=3600';localStorage.setItem('proof','saved');</script>
                </body></html>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong()); exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val owner = BrowserOwner("browser-proof", "session-one")
        val other = BrowserOwner("another-agent", "session-one")
        val url = "http://127.0.0.1:${server.address.port}/"
        try {
            val blank = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "about:blank")))
            assertEquals("", blank.text)
            runtime.command(BrowserCommand(owner, BrowserAction.Close, blank.tab.id))
            var frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Open, text = url)))
            val tab = frame.tab.id
            assertTrue(File(home, "runtime/playwright-1.64.0/.chromium-installed").isFile)
            assertTrue(frame.screenshot!!.length > 1000)
            val launches = ProcessHandle.current().descendants().use { processes ->
                processes.map { it.info().commandLine().orElse("") }.filter { "--remote-debugging-pipe" in it }.toList()
            }
            assertTrue(launches.isNotEmpty(), "Managed Chromium must use a private debugging pipe")
            assertTrue(launches.none { "--no-sandbox" in it }, "Chromium sandbox must stay enabled")
            assertTrue(runtime.tabs(other).isEmpty())
            assertFailsWith<IllegalArgumentException> { runtime.command(BrowserCommand(other, BrowserAction.Capture, tab)) }
            assertFailsWith<IllegalArgumentException> { runtime.command(BrowserCommand(owner.copy(sessionId = "other"), BrowserAction.Capture, tab)) }
            val button = frame.elements.first { it.label == "Submit" }
            val oldRevision = frame.revision
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, button.ref)))
            assertEquals("Clicked", frame.tab.title)
            assertFailsWith<IllegalArgumentException> { runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, oldRevision, button.ref)) }
            val change = frame.elements.first { it.label == "Change value" }
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, change.ref)))
            val message = frame.elements.first { it.label == "Message" }
            kotlinx.coroutines.delay(250)
            assertFailsWith<IllegalArgumentException> {
                runtime.command(BrowserCommand(owner, BrowserAction.Type, tab, frame.revision, message.ref, "overwrite"))
            }
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Capture, tab)))
            val password = frame.elements.first { it.label.startsWith("Password") }
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Type, tab, frame.revision, password.ref, "never-enter")) }
            runtime.command(BrowserCommand(owner, BrowserAction.Takeover, tab, take = true), user = true)
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Navigate, tab, text = url)) }
            runtime.command(BrowserCommand(owner, BrowserAction.Type, tab, text = "manual"), user = true)
            runtime.command(BrowserCommand(owner, BrowserAction.Takeover, tab, take = false), user = true)
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, x = 10, y = 10), user = true) }
            // Closing every Device is irrelevant: the runtime still owns and captures the tab.
            assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Capture, tab)))
            runtime.release(owner.agentId)
            assertTrue(runtime.tabs(owner).isEmpty())
            assertTrue(File(home, "profiles/${owner.agentId}/Default/Cookies").isFile || File(home, "profiles/${owner.agentId}/Default/Network/Cookies").isFile)
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "${url}check")))
            assertEquals("Login persisted", frame.tab.title)
            val isolated = assertNotNull(runtime.command(BrowserCommand(other, BrowserAction.Open, text = "${url}check")))
            assertEquals("Isolated", isolated.tab.title)
            assertNotEquals(tab, frame.tab.id)
            val proof = File(home, "browser-proof.jpg"); proof.writeBytes(kotlin.io.encoding.Base64.decode(frame.screenshot!!))
            println("Managed Chromium proof: ${proof.absolutePath}")
        } finally { runtime.close(); server.stop(0) }
        assertFailsWith<IllegalStateException> { runtime.tabs(owner) }
        Unit
    }
}
