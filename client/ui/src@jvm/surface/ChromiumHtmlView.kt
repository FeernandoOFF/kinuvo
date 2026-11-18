package kinuvo.ui.surface

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kinuvo.protocol.browser.findChromium
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import java.awt.event.KeyEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun devToolsPort(content: String): Int = content.lineSequence().first().trim().toInt().also { require(it in 1..65535) }

internal data class CdpCommand(val method: String, val params: JsonObject)

internal fun chromiumCommand(command: JsonObject): CdpCommand {
    fun text(key: String) = command[key]?.jsonPrimitive?.content.orEmpty()
    fun number(key: String) = command[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val flags = number("modifiers").toInt()
    val modifiers = (if (flags and KeyEvent.ALT_DOWN_MASK != 0) 1 else 0) or
        (if (flags and KeyEvent.CTRL_DOWN_MASK != 0) 2 else 0) or
        (if (flags and KeyEvent.META_DOWN_MASK != 0) 4 else 0) or
        (if (flags and KeyEvent.SHIFT_DOWN_MASK != 0) 8 else 0)
    var method = ""
    val params = buildJsonObject {
        when (text("op")) {
            "eval" -> { method = "Runtime.evaluate"; put("expression", text("js")) }
            "text" -> { method = "Input.insertText"; put("text", text("text")) }
            "focus" -> { method = "Emulation.setFocusEmulationEnabled"; put("enabled", command["focused"] ?: JsonPrimitive(false)) }
            "size" -> {
                method = "Emulation.setDeviceMetricsOverride"
                put("width", number("width").toInt().coerceIn(1, 4096)); put("height", number("height").toInt().coerceIn(1, 2048))
                put("deviceScaleFactor", number("density").takeIf { it > 0 } ?: 1.0); put("mobile", false)
                putJsonObject("viewport") {
                    put("x", 0); put("y", 0); put("scale", 1)
                    put("width", number("width").toInt().coerceIn(1, 4096)); put("height", number("height").toInt().coerceIn(1, 2048))
                }
            }
            "key" -> {
                val code = number("code").toInt()
                val key = when (code) {
                    KeyEvent.VK_BACK_SPACE -> "Backspace"; KeyEvent.VK_ENTER -> "Enter"; KeyEvent.VK_TAB -> "Tab"
                    KeyEvent.VK_ESCAPE -> "Escape"; KeyEvent.VK_DELETE -> "Delete"
                    KeyEvent.VK_LEFT -> "ArrowLeft"; KeyEvent.VK_RIGHT -> "ArrowRight"; KeyEvent.VK_UP -> "ArrowUp"; KeyEvent.VK_DOWN -> "ArrowDown"
                    else -> text("text").ifEmpty { KeyEvent.getKeyText(code) }
                }
                val physical = when (code) {
                    in KeyEvent.VK_A..KeyEvent.VK_Z -> "Key${code.toChar()}"
                    in KeyEvent.VK_0..KeyEvent.VK_9 -> "Digit${code.toChar()}"
                    KeyEvent.VK_ENTER -> "Enter"; KeyEvent.VK_SPACE -> "Space"
                    else -> key
                }
                method = "Input.dispatchKeyEvent"
                put("type", if (text("up") == "true") "keyUp" else "keyDown")
                put("key", key); put("code", physical); put("windowsVirtualKeyCode", code); put("modifiers", modifiers)
                if (text("up") != "true" && modifiers and 7 == 0) {
                    put("text", if (code == KeyEvent.VK_ENTER) "\r" else text("text").filter { !it.isISOControl() })
                }
            }
            "mouse", "scroll" -> {
                method = "Input.dispatchMouseEvent"
                val kind = text("kind")
                put("type", if (text("op") == "scroll") "mouseWheel" else when (kind) { "down" -> "mousePressed"; "up" -> "mouseReleased"; else -> "mouseMoved" })
                put("x", number("x")); put("y", number("y")); put("modifiers", modifiers)
                if (text("op") == "scroll") { put("deltaX", -number("dx")); put("deltaY", -number("dy")) }
                else {
                    val right = text("secondary") == "true"
                    put("button", if (kind == "move") "none" else if (right) "right" else "left")
                    put("buttons", if (kind == "down" || kind == "drag") if (right) 2 else 1 else 0)
                    put("clickCount", number("clicks").toInt())
                }
            }
            else -> error("Unsupported HTML host command: ${text("op")}")
        }
    }
    return CdpCommand(method, params)
}

internal class ChromiumHtmlView(executable: String = findChromium() ?: error(
    if (System.getProperty("os.name").startsWith("Windows")) "HTML Surfaces need Microsoft Edge or Google Chrome."
    else "HTML Surfaces need Chromium or Google Chrome (e.g. sudo apt install chromium)."
)) : HtmlHost {
    private val profile = Files.createTempDirectory("kinuvo-html-").toFile()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "html-chromium").apply { isDaemon = true } }
    private val messages = ArrayBlockingQueue<String>(256)
    private val frame = AtomicReference<ByteArray?>()
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private var process: Process? = null
    private var socket: WebSocket? = null
    @Volatile private var closed = false
    private val shutdown = Thread { close() }
    private val ready = CompletableFuture<Unit>()
    @Volatile private var mainFrame = ""
    private val contexts = ConcurrentHashMap<Int, String>()
    private val loaded = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    private val failure = AtomicReference<Exception?>()

    init {
        Runtime.getRuntime().addShutdownHook(shutdown)
        worker.execute {
            try { start(executable); ready.complete(Unit) }
            catch (e: Exception) { ready.completeExceptionally(e); close() }
        }
    }

    private fun start(executable: String) {
        synchronized(this) {
            check(!closed)
            process = ProcessBuilder(executable, "--headless=new", "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
            "--user-data-dir=${profile.absolutePath}", "--no-first-run", "--no-default-browser-check", "--disable-extensions",
            "--mute-audio", "--disable-background-networking", "--disable-sync", "about:blank")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
        val portFile = profile.resolve("DevToolsActivePort")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!portFile.exists()) {
            check(!closed && process?.isAlive == true && System.nanoTime() < deadline) { "Chromium did not start" }
            Thread.sleep(25)
        }
        val port = devToolsPort(portFile.readText())
        val client = HttpClient.newHttpClient()
        val targets = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list")).build(), HttpResponse.BodyHandlers.ofString())
        val page = Json.parseToJsonElement(targets.body()).jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "page" }.jsonObject
        socket = client.newWebSocketBuilder().buildAsync(URI(page.getValue("webSocketDebuggerUrl").jsonPrimitive.content), object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<Void>? {
                buffer.append(data)
                if (last) { receive(Json.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
                webSocket.request(1)
                return null
            }
            override fun onError(webSocket: WebSocket, error: Throwable) { pending.values.forEach { it.completeExceptionally(error) } }
        }).get(10, TimeUnit.SECONDS)
        call("Page.enable")
        call("Page.setLifecycleEventsEnabled", buildJsonObject { put("enabled", true) })
        mainFrame = call("Page.getFrameTree").getValue("frameTree").jsonObject.getValue("frame").jsonObject.getValue("id").jsonPrimitive.content
        call("Runtime.enable")
        call("Runtime.addBinding", buildJsonObject { put("name", "kinuvoPost") })
        call("Page.setInterceptFileChooserDialog", buildJsonObject { put("enabled", true) })
        call("Fetch.enable", buildJsonObject { putJsonArray("patterns") { add(buildJsonObject { put("urlPattern", "*"); put("resourceType", "Document") }) } })
        call("Page.startScreencast", buildJsonObject { put("format", "png"); put("everyNthFrame", 1) })
    }

    private fun receive(event: JsonObject) {
        event["id"]?.jsonPrimitive?.intOrNull?.let { pending.remove(it)?.complete(event); return }
        val p = event["params"]?.jsonObject ?: return
        when (event["method"]?.jsonPrimitive?.content) {
            "Runtime.executionContextCreated" -> {
                val context = p.getValue("context").jsonObject
                val aux = context["auxData"]?.jsonObject
                if (aux?.get("isDefault")?.jsonPrimitive?.boolean == true)
                    contexts[context.getValue("id").jsonPrimitive.int] = aux.getValue("frameId").jsonPrimitive.content
            }
            "Runtime.executionContextDestroyed" -> contexts.remove(p.getValue("executionContextId").jsonPrimitive.int)
            "Runtime.executionContextsCleared" -> contexts.clear()
            "Page.frameNavigated" -> {
                val navigated = p.getValue("frame").jsonObject
                if (navigated["parentId"] == null) mainFrame = navigated.getValue("id").jsonPrimitive.content
            }
            "Page.lifecycleEvent" -> if (p["name"]?.jsonPrimitive?.content == "load" && p["frameId"]?.jsonPrimitive?.content == mainFrame)
                loaded.computeIfAbsent(p.getValue("loaderId").jsonPrimitive.content) { CompletableFuture() }.complete(Unit)
            "Runtime.bindingCalled" -> if (p["name"]?.jsonPrimitive?.content == "kinuvoPost" && contexts[p["executionContextId"]?.jsonPrimitive?.int] == mainFrame) {
                val value = p.getValue("payload").jsonPrimitive.content
                if (value.toByteArray().size <= 512 * 1024) messages.offer(value)
            }
            "Page.screencastFrame" -> {
                frame.set(Base64.getDecoder().decode(p.getValue("data").jsonPrimitive.content))
                emit("Page.screencastFrameAck", buildJsonObject { put("sessionId", p.getValue("sessionId")) })
            }
            "Fetch.requestPaused" -> {
                val url = p.getValue("request").jsonObject.getValue("url").jsonPrimitive.content
                emit(if (url == "about:blank" || url == "about:srcdoc") "Fetch.continueRequest" else "Fetch.failRequest", buildJsonObject {
                    put("requestId", p.getValue("requestId")); if (url != "about:blank" && url != "about:srcdoc") put("errorReason", "BlockedByClient")
                })
            }
        }
    }

    private fun emit(method: String, params: JsonObject, id: Int = ids.incrementAndGet()) {
        socket?.sendText(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString(), true)
    }

    private fun call(method: String, params: JsonObject = buildJsonObject {}) : JsonObject {
        val id = ids.incrementAndGet()
        val result = CompletableFuture<JsonObject>()
        pending[id] = result
        try {
            emit(method, params, id)
            val response = result.get(10, TimeUnit.SECONDS)
            check(response["error"] == null) { response.toString() }
            return response["result"]!!.jsonObject
        } finally { pending.remove(id) }
    }

    override fun send(command: JsonObject) {
        if (closed) return
        worker.execute {
            try {
                ready.join()
                if (command["op"]?.jsonPrimitive?.content == "load") {
                    loaded.clear()
                    val target = call("Page.navigate", buildJsonObject { put("url", "about:blank") })
                    val loader = target.getValue("loaderId").jsonPrimitive.content
                    loaded.computeIfAbsent(loader) { CompletableFuture() }.get(10, TimeUnit.SECONDS)
                    loaded.clear()
                    val frameId = target.getValue("frameId")
                    val html = command.getValue("html").jsonPrimitive.content.replaceFirst("<head>", "<head><script>window.kinuvoNative={postMessage:raw=>kinuvoPost(raw)};</script>")
                    call("Page.setDocumentContent", buildJsonObject { put("frameId", frameId); put("html", html) })
                } else chromiumCommand(command).let { call(it.method, it.params) }
            } catch (e: Exception) { if (!closed) failure.compareAndSet(null, e) }
        }
    }

    override fun poll(): String? { failure.get()?.let { throw it }; if (ready.isCompletedExceptionally) ready.join(); return messages.poll() }
    override fun image(): ImageBitmap? = frame.getAndSet(null)?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        socket?.abort()
        pending.values.forEach { it.cancel(false) }
        loaded.values.forEach { it.cancel(false) }
        process?.let { chrome ->
            val children = chrome.descendants().toList()
            children.forEach { it.destroyForcibly() }
            chrome.destroyForcibly()
            runCatching { chrome.waitFor(5, TimeUnit.SECONDS) }
            children.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
        }
        profile.deleteRecursively()
        worker.shutdownNow()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
}
