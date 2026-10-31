package kinuvo.harness.browser

import com.sun.net.httpserver.HttpServer
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class BrowserRuntimeTest {
    private suspend fun control(runtime: BrowserRuntime, owner: BrowserOwner, tab: String, take: Boolean) {
        val gate = runtime.gate(owner, tab)
        if (take) gate.acquire("fixture-device", gate.state.value.generation)
        else gate.release("fixture-device", gate.state.value.generation)
        runtime.controlChanged(owner, tab)
    }

    @Test fun navigationAllowsPrivateHttpButRejectsFilesAndCustomSchemes() {
        listOf("http://127.0.0.1:8080", "http://192.168.1.1", "https://lab.internal", "about:blank").forEach { assertTrue(BrowserRuntime.allowed(it), it) }
        listOf("file:///etc/passwd", "javascript:alert(1)", "data:text/html,hi", "chrome://settings", "https://user:password@host.test", "https:foo").forEach { assertFalse(BrowserRuntime.allowed(it), it) }
    }

    @Test fun continuousFramesCoexistWithSemanticOperationsAndHeldTabsRestore() = runBlocking {
        val home = java.nio.file.Files.createTempDirectory("kinuvo-stream-proof-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val color = if (exchange.requestURI.path == "/second") "blue" else "red"
            val html = "<html><body style='background:$color'><button>Semantic button</button></body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val owner = BrowserOwner("stream-agent")
        val url = "http://127.0.0.1:${server.address.port}"
        var runtime = BrowserRuntime(home)
        try {
            val first = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Open, text = url)))
            val closed = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Open, text = url)))
            runtime.command(BrowserCommand(owner, BrowserAction.Close, closed.tab.id))
            val source = runtime.screen(owner, first.tab.id)
            assertEquals(ScreenDelivery.Continuous, source.capabilities.delivery)
            assertTrue(source.capabilities.keys.containsAll(listOf("Tab", "Escape", "Backspace", "ArrowUp", "ArrowDown", "Enter")))
            assertFalse("Home" in source.capabilities.keys)
            assertTrue("Control" in source.capabilities.modifiers)
            val frames = Channel<ScreenSample>(Channel.UNLIMITED)
            val watching = launch { source.frames().collect { frames.send(it) } }
            val red = withTimeout(15_000) { frames.receive() }
            assertEquals(1280, red.width)
            assertEquals(800, red.height)
            assertTrue(red.image.length > 1000)
            assertTrue(assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Capture, first.tab.id))).elements.isNotEmpty())
            runtime.command(BrowserCommand(owner, BrowserAction.Navigate, first.tab.id, text = "$url/second"))
            val blue = withTimeout(15_000) {
                var sample = frames.receive()
                while (sample.image == red.image) sample = frames.receive()
                sample
            }
            assertNotEquals(red.image, blue.image)
            assertFailsWith<IllegalArgumentException> {
                runtime.screen(owner.copy(agentId = "wrong"), first.tab.id).frames().first()
            }
            watching.cancelAndJoin()
            withTimeout(5000) { while (runtime.liveStreams() != 0) delay(10) }
            assertEquals(0, runtime.liveStreams())
            // A fresh CDP session must work after stopping and detaching the previous one.
            val again = async { withTimeout(15_000) { source.frames().first() } }
            assertTrue(again.await().image.isNotEmpty())
            control(runtime, owner, first.tab.id, true)
            runtime.close()
            val records = File(home, "tabs.json")
            records.writeText(records.readText().replace("\"agentId\":\"stream-agent\"", "\"agentId\":\"stream-agent\",\"sessionId\":\"legacy-session\""))
            runtime = BrowserRuntime(home)
            val restored = runtime.tabs(owner)
            assertEquals(listOf(first.tab.id), restored.map { it.id })
            assertTrue(restored.single().takeover)
            assertEquals("$url/second", restored.single().url)
            assertTrue(runtime.tabs(owner.copy(agentId = "other")).isEmpty())
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Navigate, first.tab.id, text = url)) }
            control(runtime, owner, first.tab.id, false)
            runtime.command(BrowserCommand(owner, BrowserAction.Capture, first.tab.id))
            runtime.command(BrowserCommand(owner, BrowserAction.Close, first.tab.id))
            runtime.close()
            runtime = BrowserRuntime(home)
            assertTrue(runtime.tabs(owner).isEmpty())
            println("Continuous streaming and held recovery proof: ${home.absolutePath}")
        } finally { runtime.close(); server.stop(0) }
    }

    @Test fun managedChromiumOwnershipReferencesTakeoverPersistenceAndLifecycle() = runBlocking {
        val home = java.nio.file.Files.createTempDirectory("kinuvo-browser-proof-").toFile()
        val runtime = BrowserRuntime(home)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val checking = exchange.requestURI.path == "/check"
            val html = if (checking) """<script>document.title = localStorage.getItem('proof') === 'saved' && document.cookie.includes('login=persistent') ? 'Login persisted' : 'Isolated';</script>""".toByteArray() else """<html><head><title>Browser proof</title></head><body>
                <button onclick="document.title='Clicked';this.textContent='Done'">Submit</button>
                <input aria-label="Message"><button onclick="setTimeout(() => document.querySelector('input').value = 'changed', 1000)">Change value</button><input type="password" aria-label="Password">
                <script>document.cookie='login=persistent; max-age=3600';localStorage.setItem('proof','saved');</script>
                </body></html>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong()); exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val owner = BrowserOwner("browser-proof")
        val other = BrowserOwner("another-agent")
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
            assertFailsWith<IllegalArgumentException> { runtime.command(BrowserCommand(owner.copy(agentId = "other"), BrowserAction.Capture, tab)) }
            val button = frame.elements.first { it.label == "Submit" }
            val oldRevision = frame.revision
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, button.ref)))
            assertEquals("Clicked", frame.tab.title)
            assertFailsWith<IllegalArgumentException> { runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, oldRevision, button.ref)) }
            val change = frame.elements.first { it.label == "Change value" }
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, change.ref)))
            val message = frame.elements.first { it.label == "Message" }
            kotlinx.coroutines.delay(1500)
            assertFailsWith<IllegalArgumentException> {
                runtime.command(BrowserCommand(owner, BrowserAction.Type, tab, frame.revision, message.ref, "overwrite"))
            }
            frame = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Capture, tab)))
            val password = frame.elements.first { it.label.startsWith("Password") }
            assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Type, tab, frame.revision, password.ref, "fixture-value")))
            control(runtime, owner, tab, true)
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Navigate, tab, text = url)) }
            runtime.gate(owner, tab).input("fixture-device", runtime.gate(owner, tab).state.value.generation) { runtime.userCommand(BrowserCommand(owner, BrowserAction.Type, tab, text = "manual")) }
            control(runtime, owner, tab, false)
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
