package kinuvo.ui.surface

import kinuvo.protocol.browser.findChromium
import kotlinx.serialization.json.*
import java.awt.event.KeyEvent
import kotlin.test.*

class ChromiumHtmlViewTest {
    private fun command(op: String, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("op", op); body() }

    @Test fun activePort() {
        assertEquals(9222, devToolsPort("9222\n/devtools/browser/abc\n"))
        assertFails { devToolsPort("0\n") }
        assertFails { devToolsPort("invalid") }
    }

    @Test fun inputAndCommands() {
        val key = chromiumCommand(command("key") { put("code", KeyEvent.VK_A); put("text", "a"); put("modifiers", KeyEvent.CTRL_DOWN_MASK) })
        assertEquals("Input.dispatchKeyEvent", key.method)
        assertEquals("KeyA", key.params["code"]?.jsonPrimitive?.content)
        assertEquals(2, key.params["modifiers"]?.jsonPrimitive?.int)
        assertNull(key.params["text"])
        val mouse = chromiumCommand(command("mouse") { put("kind", "down"); put("secondary", true); put("clicks", 2); put("x", 15) })
        assertEquals("mousePressed", mouse.params["type"]?.jsonPrimitive?.content)
        assertEquals("right", mouse.params["button"]?.jsonPrimitive?.content)
        assertEquals(2, mouse.params["clickCount"]?.jsonPrimitive?.int)
        val size = chromiumCommand(command("size") { put("width", 500); put("height", 300); put("density", 2) })
        assertEquals(2.0, size.params["deviceScaleFactor"]?.jsonPrimitive?.double)
        assertEquals("Runtime.evaluate", chromiumCommand(command("eval") { put("js", "1+1") }).method)
        assertEquals("Input.insertText", chromiumCommand(command("text") { put("text", "hello") }).method)
    }

    @Test fun chromiumBridgeAndFrame() {
        val executable = findChromium() ?: return
        ChromiumHtmlView(executable).use { host ->
            fun awaitMessage(value: String) {
                val deadline = System.nanoTime() + 20_000_000_000
                while (System.nanoTime() < deadline) {
                    if (host.poll()?.contains(value) == true) return
                    Thread.sleep(20)
                }
                fail("Missing bridge message: $value")
            }
            host.send(command("size") { put("width", 400); put("height", 300); put("density", 2) })
            repeat(10) { iteration ->
                host.send(command("load") { put("html", htmlSurfaceDocument("""
                    <body style="background:red;margin:0"><input style="width:200px;height:40px" oninput="parent.postMessage({jsonrpc:'2.0',method:'typed',value:this.value},'*')"><script>
                    parent.postMessage({jsonrpc:'2.0',method:'ready-$iteration'},'*');
                    requestAnimationFrame(()=>requestAnimationFrame(()=>parent.postMessage({jsonrpc:'2.0',method:'painted-$iteration'},'*')));
                    addEventListener('message',e=>parent.postMessage({jsonrpc:'2.0',method:'pong'},'*'));
                    </script></body>
                """, false)) })
                awaitMessage("ready-$iteration")
                awaitMessage("painted-$iteration")
            }
            host.send(command("eval") { put("js", htmlSurfaceDelivery("""{"jsonrpc":"2.0","method":"ping"}""")) })
            awaitMessage("pong")
            host.send(command("focus") { put("focused", true) })
            for (kind in listOf("down", "up")) host.send(command("mouse") { put("kind", kind); put("x", 30); put("y", 20); put("clicks", 1) })
            host.send(command("text") { put("text", "hello") })
            awaitMessage("hello")
            var dimensions = "none"
            val deadline = System.nanoTime() + 10_000_000_000
            while (System.nanoTime() < deadline) {
                val image = host.image()
                if (image != null) dimensions = "${image.width}x${image.height}"
                if (image != null && image.width == 800 && image.height == 600) return
                Thread.sleep(20)
            }
            fail("Missing density-scaled Chromium frame; last: $dimensions")
        }
    }
}
