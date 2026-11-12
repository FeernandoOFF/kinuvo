package kinuvo.ui

import kinuvo.ui.surface.MacHtmlView
import kinuvo.ui.surface.htmlSurfaceDocument
import kinuvo.ui.surface.htmlSurfaceDelivery
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopHtmlSurfaceTest {
    @Test fun macSnapshotBridgeClickAndTyping() {
        val resources = System.getenv("KINUVO_TEST_NATIVE_HTML") ?: return
        val previous = System.getProperty("compose.application.resources.dir")
        System.setProperty("compose.application.resources.dir", resources)
        SwingUtilities.invokeAndWait { }
        try {
            MacHtmlView().use { view ->
                fun send(op: String, body: JsonObjectBuilder.() -> Unit = {}) = view.send(buildJsonObject { put("op", op); body() })
                fun message(predicate: (String) -> Boolean): String {
                    val deadline = System.nanoTime() + 10_000_000_000
                    while (System.nanoTime() < deadline) {
                        val text = view.poll()
                        if (text != null && predicate(text)) return text
                        Thread.sleep(10)
                    }
                    error("No expected message from WKWebView")
                }
                fun frame(): MacHtmlView.Frame {
                    val deadline = System.nanoTime() + 10_000_000_000
                    while (System.nanoTime() < deadline) {
                        view.pixels()?.let { return it }
                        Thread.sleep(10)
                    }
                    error("No WKWebView snapshot")
                }
                fun click(x: Int, y: Int) {
                    for (kind in listOf("down", "up")) send("mouse") { put("kind", kind); put("x", x); put("y", y) }
                }
                val started = System.nanoTime()
                send("load") { put("html", htmlSurfaceDocument("""
                    <body style="background:#ff0000;margin:0">
                    <input id="bill" style="width:200px;height:40px;font-size:24px" oninput="report(this.value)">
                    <button style="width:100px;height:40px" onclick="document.body.style.background='#00ff00';report('clicked')">Tip</button>
                    <script>
                    function report(text){parent.postMessage({jsonrpc:'2.0',method:'probe',params:{text}},'*')}
                    addEventListener('message',e=>{if(e.data.method==='ping')report('pong')});
                    report('ready');
                    </script></body>
                """.trimIndent(), false)) }
                message { it.contains("ready") }
                send("eval") { put("js", htmlSurfaceDelivery("""{"jsonrpc":"2.0","method":"ping"}""")) }
                message { it.contains("pong") }
                Thread.sleep(300)
                send("eval") { put("js", "void 0") }
                Thread.sleep(100)
                var before = frame()
                while (true) { before = view.pixels() ?: break }
                assertEquals(1400, before.width)
                assertEquals(1000, before.height)
                before.bitmap().use {
                    val color = it.getColor(10, 800)
                    assertEquals(0xffff0000.toInt(), color)
                }
                println("HTML first pixels + bridge: ${(System.nanoTime() - started) / 1_000_000} ms")
                click(250, 20)
                message { it.contains("clicked") }
                send("focus") { put("focused", true) }
                click(70, 20)
                Thread.sleep(200)
                send("text") { put("text", "123.45") }
                message { it.contains("123.45") }
                Thread.sleep(300)
                var after = frame()
                while (true) { after = view.pixels() ?: break }
                after.bitmap().use { bitmap ->
                    val color = bitmap.getColor(10, 800)
                    assertEquals(0xff00ff00.toInt(), color)
                    var changed = 0
                    before.bitmap().use { original ->
                        for (y in 10..70) for (x in 10..350) if (bitmap.getColor(x, y) != original.getColor(x, y)) changed++
                    }
                    assertTrue(changed > 100, "Typed text did not change input pixels: $changed")
                    val out = File("build/screens/html-surfaces/desktop-native-input.png")
                    out.parentFile.mkdirs()
                    Image.makeFromBitmap(bitmap).use { image -> image.encodeToData()!!.use { out.writeBytes(it.bytes) } }
                    println("HTML typed pixel changes: $changed; screenshot ${out.absolutePath}")
                }
                // Exercise the same Skia-to-Compose conversion used by the host.
                assertEquals(1400, after.image().width)
                fun stats(): JsonObject {
                    send("stats")
                    return Json.parseToJsonElement(message { it.contains("nativeStats") }).jsonObject
                }
                // Reload a static document so neither focus nor caret hints keep it dirty.
                send("load") { put("html", "<body style='background:blue'>idle</body>") }
                Thread.sleep(1000)
                val idleStart = stats()
                Thread.sleep(1500)
                val idleEnd = stats()
                assertEquals(idleStart["count"], idleEnd["count"], "Static idle page kept snapshotting")
                println("HTML native stats $idleEnd; idle 1.5 s: zero snapshots")
            }
        } finally {
            if (previous == null) System.clearProperty("compose.application.resources.dir")
            else System.setProperty("compose.application.resources.dir", previous)
        }
    }
}
