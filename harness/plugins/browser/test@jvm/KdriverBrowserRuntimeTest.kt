package kinuvo.harness.browser

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlin.test.*

class KdriverBrowserRuntimeTest {
    @Test fun restoredBackgroundTabsAndLateViewersReceiveFrames() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-restored-frames-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "<title>Static page</title><body style='background:teal'>Static content</body>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val owner = BrowserOwner("restored-frames")
        var runtime = BrowserRuntime(home)
        try {
            val address = "http://127.0.0.1:${server.address.port}"
            val first = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = address))!!.tab.id
            runtime.command(BrowserCommand(owner, BrowserAction.Open, text = address))
            runtime.close()
            runtime = BrowserRuntime(home)
            assertEquals(2, runtime.tabs(owner).size)
            val initial = CompletableDeferred<ScreenSample>()
            val thumbnail = launch { runtime.screen(owner, first).frames(true).collect { initial.complete(it) } }
            try {
                assertEquals(1280, withTimeout(10_000) { initial.await() }.width)
                delay(500)
                val late = withTimeout(5_000) { runtime.screen(owner, first).frames(true).first() }
                assertEquals(1280, late.width)
                assertEquals(800, late.height)
            } finally { thumbnail.cancelAndJoin() }
            val reopened = withTimeout(10_000) { runtime.screen(owner, first).frames(false).first() }
            assertEquals(1280, reopened.width)
        } finally { runtime.close(); server.stop(0) }
    }

    @Test fun takeoverSupportsUnicodeAndEditingShortcuts(): Unit = runBlocking {
        val home = Files.createTempDirectory("kinuvo-keyboard-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val html = """<input autofocus aria-label="Name" oninput="document.title=this.value">""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val runtime = BrowserRuntime(home)
        val owner = BrowserOwner("keyboard-agent")
        try {
            assertTrue(runtime.presentation(owner).headless)
            val tab = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "http://127.0.0.1:${server.address.port}"))!!.tab.id
            val gate = runtime.gate(owner, tab)
            gate.acquire("test-device", gate.state.value.generation)
            runtime.controlChanged(owner, tab)
            suspend fun input(action: BrowserAction, text: String) = runtime.userCommand(BrowserCommand(owner, action, tab, text = text))!!
            assertEquals("Hello 👋", input(BrowserAction.Type, "Hello 👋").tab.title)
            input(BrowserAction.Key, if (System.getProperty("os.name").contains("Mac")) "Meta+A" else "Control+A")
            assertEquals("日本語", input(BrowserAction.Type, "日本語").tab.title)
            assertEquals("日本", input(BrowserAction.Key, "Backspace").tab.title)
            input(BrowserAction.Key, "Home")
            assertEquals("本", input(BrowserAction.Key, "Delete").tab.title)
            val resized = runtime.userCommand(BrowserCommand(owner, BrowserAction.Resize, tab, width = 390, height = 600))!!
            assertEquals(390, resized.width)
            assertEquals(600, resized.height)
            val sample = withTimeout(10_000) { runtime.screen(owner, tab).frames().first() }
            assertEquals(390, sample.width)
            assertEquals(600, sample.height)
            runtime.userCommand(BrowserCommand(owner, BrowserAction.Click, tab, x = 300, y = 500))
            assertFailsWith<IllegalStateException> { runtime.userCommand(BrowserCommand(owner, BrowserAction.Click, tab, x = 500, y = 500)) }
            val hasDisplay = !System.getProperty("os.name").contains("Linux") || !System.getenv("DISPLAY").isNullOrBlank()
            if (hasDisplay) {
                val visible = runtime.presentation(BrowserPresentationRequest(owner, headless = false, hidden = false))
                assertEquals(false, visible.runningHeadless)
                val resumed = runtime.gate(owner, tab)
                resumed.acquire("test-device", resumed.state.value.generation)
                runtime.controlChanged(owner, tab)
                assertEquals("kept", input(BrowserAction.Type, "kept").tab.title)
                val hidden = runtime.presentation(BrowserPresentationRequest(owner, headless = false, hidden = true))
                assertTrue(hidden.hidden)
                runtime.presentation(BrowserPresentationRequest(owner, headless = false, hidden = false))
                assertEquals("kept", runtime.userCommand(BrowserCommand(owner, BrowserAction.Capture, tab))!!.tab.title)
                val streaming = CompletableDeferred<Unit>()
                val oldStream = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { runtime.screen(owner, tab).frames().collect { streaming.complete(Unit) } }.exceptionOrNull()
                }
                withTimeout(10_000) { streaming.await() }
                val headless = runtime.presentation(BrowserPresentationRequest(owner, headless = true, hidden = false))
                assertEquals(true, headless.runningHeadless)
                assertNotNull(withTimeout(10_000) { oldStream.await() })
                assertEquals(tab, runtime.tabs(owner).single().id)
                assertTrue(runtime.gate(owner, tab).state.value.suspended)
                assertEquals(1280, withTimeout(10_000) { runtime.screen(owner, tab).frames().first() }.width)
            }
            runtime.close()
            val restarted = BrowserRuntime(home)
            try {
                assertTrue(restarted.presentation(owner).headless)
                assertNull(restarted.presentation(owner).runningHeadless)
                assertEquals(tab, restarted.tabs(owner).single().id)
                assertEquals(true, restarted.presentation(owner).runningHeadless)
            } finally { restarted.close() }
        } finally { runtime.close(); server.stop(0) }
    }

    @Test fun agentAndTakeoverCanNavigateHistoryWithoutReplayingInputs() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-history-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val html = "<title>${exchange.requestURI.path}</title><p>${exchange.requestURI.path}</p>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val runtime = BrowserRuntime(home)
        val owner = BrowserOwner("history-agent")
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val tab = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "$base/first"))!!.tab.id
            suspend fun command(action: BrowserAction, text: String? = null) = runtime.command(BrowserCommand(owner, action, tab, text = text))!!
            command(BrowserAction.Navigate, "$base/second")
            assertEquals("/first", command(BrowserAction.Back).tab.title)
            assertEquals("/second", command(BrowserAction.Forward).tab.title)
            assertEquals("/second", command(BrowserAction.Forward).tab.title)
            command(BrowserAction.Back)
            command(BrowserAction.Navigate, "$base/third")
            assertEquals("/third", command(BrowserAction.Forward).tab.title)
            val gate = runtime.gate(owner, tab)
            gate.acquire("test-device", gate.state.value.generation)
            runtime.controlChanged(owner, tab)
            assertFailsWith<IllegalStateException> { command(BrowserAction.Back) }
            assertEquals("/first", runtime.userCommand(BrowserCommand(owner, BrowserAction.Back, tab))!!.tab.title)
            assertEquals("/third", runtime.userCommand(BrowserCommand(owner, BrowserAction.Forward, tab))!!.tab.title)
        } finally { runtime.close(); server.stop(0) }
    }

    @Test fun popupsInheritAgentAndSuspendedTakeover() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-popup-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val html = if (exchange.requestURI.path == "/popup") "<title>Popup</title>Popup content"
                else """<button style="position:absolute;left:10px;top:100px" onclick="window.open('/popup')">Open popup</button>"""
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val runtime = BrowserRuntime(home)
        val owner = BrowserOwner("popup-agent")
        try {
            val opened = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "http://127.0.0.1:${server.address.port}"))!!
            val gate = runtime.gate(owner, opened.tab.id)
            gate.acquire("test-device", gate.state.value.generation)
            runtime.controlChanged(owner, opened.tab.id)
            runtime.userCommand(BrowserCommand(owner, BrowserAction.Click, opened.tab.id, x = 20, y = 110))
            val popup = withTimeout(10_000) {
                var candidate: BrowserTab? = null
                while (candidate == null) { candidate = runtime.tabs(owner).firstOrNull { it.id != opened.tab.id && it.title == "Popup" }; delay(50) }
                candidate
            }
            val popupGate = runtime.gate(owner, popup.id)
            assertEquals("test-device", popupGate.state.value.holder)
            assertTrue(popupGate.state.value.suspended)
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Capture, popup.id)) }
            assertTrue(runtime.tabs(BrowserOwner("other-agent")).isEmpty())
        } finally { runtime.close(); server.stop(0) }
    }

    @Test fun referencesLiveViewTakeoverAndPersistentAgentProfiles() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-kdriver-test-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val html = """<html><body><input aria-label="Name"><button onclick="document.body.append('clicked')">Save</button><script>document.body.append(document.cookie || 'no-cookie');document.cookie='fixture=present;path=/;max-age=3600';</script></body></html>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val owner = BrowserOwner("first")
        val address = "http://127.0.0.1:${server.address.port}"
        var runtime = BrowserRuntime(home)
        try {
            val opened = assertNotNull(runtime.command(BrowserCommand(owner, BrowserAction.Open, text = address)))
            assertTrue(opened.text.contains("no-cookie"))
            assertTrue(opened.screenshot!!.isNotEmpty())
            val input = opened.elements.first { it.label == "Name" }
            val typed = runtime.command(BrowserCommand(owner, BrowserAction.Type, opened.tab.id, opened.revision, input.ref, "Example"))!!
            val button = typed.elements.first { it.label == "Save" }
            val clicked = runtime.command(BrowserCommand(owner, BrowserAction.Click, opened.tab.id, typed.revision, button.ref))!!
            assertTrue(clicked.text.contains("clicked"))
            assertFailsWith<IllegalArgumentException> { runtime.command(BrowserCommand(owner, BrowserAction.Type, opened.tab.id, opened.revision, input.ref, "stale")) }
            assertFailsWith<IllegalArgumentException> { runtime.gate(BrowserOwner("second"), opened.tab.id) }
            val isolated = runtime.command(BrowserCommand(BrowserOwner("second"), BrowserAction.Open, text = address))!!
            assertTrue(isolated.text.contains("no-cookie"))
            val frame = withTimeout(15_000) { runtime.screen(owner, opened.tab.id).frames().first() }
            assertEquals(1280, frame.width)
            assertEquals(800, frame.height)
            val gate = runtime.gate(owner, opened.tab.id)
            gate.acquire("test-device", gate.state.value.generation)
            runtime.controlChanged(owner, opened.tab.id)
            assertFailsWith<IllegalStateException> { runtime.command(BrowserCommand(owner, BrowserAction.Capture, opened.tab.id)) }
            runtime.userCommand(BrowserCommand(owner, BrowserAction.Type, opened.tab.id, text = " user"))
            runtime.close()
            runtime = BrowserRuntime(home)
            assertTrue(runtime.tabs(owner).single().takeover)
            val restoredGate = runtime.gate(owner, opened.tab.id)
            assertTrue(restoredGate.state.value.suspended)
            restoredGate.release("test-device", restoredGate.state.value.generation)
            runtime.controlChanged(owner, opened.tab.id)
            val restored = runtime.command(BrowserCommand(owner, BrowserAction.Capture, opened.tab.id))!!
            assertTrue(restored.text.contains("fixture=present"))
            assertTrue(restored.elements.any { it.label == "Name" })
            runtime.command(BrowserCommand(owner, BrowserAction.Close, opened.tab.id))
            assertTrue(runtime.tabs(owner).isEmpty())
        } finally { runtime.close(); server.stop(0) }
    }
}
