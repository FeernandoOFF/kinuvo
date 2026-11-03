package kinuvo.harness.browser

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kinuvo.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BrowserDomTest {
    @Test fun formPropertiesInvalidateReferencesAndTypingRespectsEditability() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-dom-").toFile()
        val runtime = BrowserRuntime(home)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val html = """<input aria-label="Message"><input aria-label="Readonly" readonly value="original">
                <div aria-label="Editor" contenteditable="true">original</div>
                <button onclick="setTimeout(()=>document.querySelector('input').value='changed',400)">Change value</button>
                <button onclick="document.title=document.querySelector('input').value">Read value</button>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        val owner = BrowserOwner("fixture")
        try {
            var frame = runtime.command(BrowserCommand(owner, BrowserAction.Open, text = "http://127.0.0.1:${server.address.port}"))!!
            val tab = frame.tab.id
            suspend fun capture(): BrowserFrame = runtime.command(BrowserCommand(owner, BrowserAction.Capture, tab))!!
            suspend fun type(snapshot: BrowserFrame, label: String, text: String) = runtime.command(BrowserCommand(owner, BrowserAction.Type, tab, snapshot.revision, snapshot.elements.first { it.label == label }.ref, text))!!
            val change = frame.elements.first { it.label == "Change value" }
            frame = runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, change.ref))!!
            delay(600)
            assertFailsWith<IllegalStateException> { type(frame, "Message", "overwrite") }
            assertFailsWith<IllegalStateException> { type(capture(), "Readonly", "overwrite") }
            frame = type(capture(), "Editor", "Edited text")
            assertContains(frame.text, "Edited text")
            frame = type(capture(), "Message", "Ordinary text")
            val read = frame.elements.first { it.label == "Read value" }
            frame = runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, read.ref))!!
            assertEquals("Ordinary text", frame.tab.title)
        } finally { runtime.close(); server.stop(0) }
    }
}
