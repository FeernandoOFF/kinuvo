package kinuvo.harness.browser

import com.sun.net.httpserver.HttpServer
import dev.kdriver.core.browser.defaultBrowserSearchConfig
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kinuvo.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BrowserDomTest {
    @Test fun busyPagesAllowActionsAndFieldsReportTheirFinalState() = runBlocking {
        val chrome = System.getenv("KINUVO_BROWSER_EXECUTABLE")?.let(::File)
            ?: defaultBrowserSearchConfig().findChromeExecutable()?.let { File(it.toString()) }
        if (chrome?.canExecute() != true) {
            println("Skipping BrowserDomTest: Chrome is unavailable")
            return@runBlocking
        }
        val home = Files.createTempDirectory("kinuvo-dom-").toFile()
        val runtime = BrowserRuntime(home)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val html = """<input aria-label="Message"><input aria-label="Readonly" readonly value="original">
                <input aria-label="Controlled" value="original" oninput="this.value='original'">
                <input aria-label="Trusted" oninput="if(!event.isTrusted)this.value='untrusted'">
                <input aria-label="Password" type="password" value="secret">
                <select aria-label="Origin"><option value="ie">Ireland</option><option value="gb">United Kingdom</option></select>
                <ul role="menu"><li role="menuitem">Menu item</li></ul><span id="ticker"></span><script>setInterval(()=>{document.querySelector('#ticker').textContent=Date.now();document.body.dataset.tick=Date.now()},10)</script>
                <script>customElements.define('symbol-labels', class extends HTMLElement { get labels() { return Symbol() } })</script>
                <symbol-labels role="search">Custom labels</symbol-labels>
                <div aria-label="Editor" contenteditable="true">original</div>
                <button onclick="setTimeout(()=>document.querySelector('input').value='changed',400)">Change value</button>
                <button onclick="document.title=document.querySelector('input').value">Read value</button>
                <button onclick="setTimeout(()=>document.querySelector('input').setAttribute('aria-label','Renamed'),400)">Rename</button>""".toByteArray()
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
            frame = type(frame, "Message", "overwrite")
            assertEquals("overwrite", frame.elements.first { it.label == "Message" }.value)
            assertNull(frame.elements.first { it.label == "Password" }.value)
            assertEquals(listOf("Ireland", "United Kingdom"), frame.elements.first { it.label == "Origin" }.options)
            val rejected = assertFailsWith<IllegalStateException> { type(capture(), "Readonly", "overwrite") }
            assertContains(rejected.message.orEmpty(), "Not performed:")
            assertFalse(rejected.message.orEmpty().contains("outcome may be unknown"))
            val reverted = assertFailsWith<IllegalStateException> { type(capture(), "Controlled", "changed") }
            assertContains(reverted.message.orEmpty(), "did not stick")
            assertContains(reverted.message.orEmpty(), "original")
            assertContains(reverted.message.orEmpty(), "outcome may be unknown")
            frame = type(capture(), "Trusted", "trusted input")
            assertEquals("trusted input", frame.elements.first { it.label == "Trusted" }.value)
            frame = type(capture(), "Origin", "United Kingdom")
            assertEquals("gb", frame.elements.first { it.label == "Origin" }.value)
            frame = type(frame, "Origin", "ie")
            assertEquals("ie", frame.elements.first { it.label == "Origin" }.value)
            frame = type(frame, "Origin", " united kingdom ")
            assertEquals("gb", frame.elements.first { it.label == "Origin" }.value)
            val missing = assertFailsWith<IllegalStateException> { type(frame, "Origin", "Unknown") }
            assertContains(missing.message.orEmpty(), "Not performed:")
            assertContains(missing.message.orEmpty(), "Ireland (ie)")
            frame = type(capture(), "Editor", "Edited text")
            assertContains(frame.text, "Edited text")
            frame = type(capture(), "Message", "Ordinary text")
            val read = frame.elements.first { it.label == "Read value" }
            frame = runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, read.ref))!!
            assertEquals("Ordinary text", frame.tab.title)
            val rename = frame.elements.first { it.label == "Rename" }
            frame = runtime.command(BrowserCommand(owner, BrowserAction.Click, tab, frame.revision, rename.ref))!!
            delay(600)
            val changedTarget = assertFailsWith<IllegalStateException> { type(frame, "Message", "wrong target") }
            assertContains(changedTarget.message.orEmpty(), "Not performed: element role or label changed")
        } finally { runtime.close(); server.stop(0) }
    }
}
