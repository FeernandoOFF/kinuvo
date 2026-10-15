package kinuvo.harness.cua

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kinuvo.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/** cua-driver's tools, called with their JSON arguments; the result is the tool's JSON with any image under `image`. */
interface Driver {
    suspend fun call(tool: String, args: JsonObject): JsonObject
    suspend fun parameters(tool: String): Set<String>? = null
    /** Renews a bounded display wake lease, or releases it when inactive. Unsupported platforms do nothing. */
    fun keepAwake(active: Boolean) {}
    fun close() {}
}

/**
 * This computer's whole desktop, for the desktop app to offer as its Device's Computer: screenshots of the main display,
 * elements of the frontmost window when its accessibility tree resolves, and input by element token or by screenshot
 * pixels, which cua-driver maps back to the screen. [connect] is null when cua-driver isn't installed.
 */
class DesktopComputer(private val connect: (() -> Driver)? = findDriver()?.let { path -> { McpDriver(path) } }) {
    val platform = System.getProperty("os.name").orEmpty().let { os ->
        when { "mac" in os.lowercase() -> "macOS"; "win" in os.lowercase() -> "Windows"; else -> "Linux" }
    }
    /** Whether cua-driver is installed; it also needs Accessibility and Screen Recording (`cua-driver permissions grant`). */
    val ready get() = connect != null

    private class Element(val token: String?, val pid: Long?, val password: Boolean = false)
    private class Capture(val id: String, val elements: Map<Int, Element>)
    private val lock = Mutex()
    private var driver: Driver? = null
    private var last: Capture? = null
    /** Screenshot pixels per screen point in the last capture. */
    private var perPoint = 1.0
    private var session = newSession()

    fun close() { driver?.close(); driver = null }

    suspend fun perform(request: ComputerRequest): ComputerResult = lock.withLock {
        val connect = connect ?: return ComputerResult(DeviceToolStatus.Unavailable, "Install cua-driver (https://github.com/trycua/cua) and run `cua-driver permissions grant`")
        val d = driver ?: connect().also { driver = it }
        try {
            d.keepAwake(request.keepAwake)
            val error = request.action?.let { act(d, it, request) }
            if (request.action != null) delay(300)
            val screen = capture(d, request.screenshot, request.elements)
            ComputerResult(if (error == null) DeviceToolStatus.Ready else DeviceToolStatus.Denied, error, screen)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            close()
            ComputerResult(DeviceToolStatus.Unavailable, "cua-driver failed: ${e.message}")
        }
    }

    /**
     * Calls [name] in this connection's cua-driver session, which maps screenshot pixels back to the screen. Sessions
     * belong to the connection and end when idle, so an ended one is replaced by a new one.
     */
    private suspend fun Driver.tool(name: String, args: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val supported = parameters(name)
        fun arguments() = buildJsonObject { put("session", session); args() }.let { all ->
            if (supported == null) all else JsonObject(all.filterKeys { it in supported })
        }
        val result = call(name, arguments())
        if (result["error"]?.jsonPrimitive?.contentOrNull?.contains("session has ended") != true) return result
        session = newSession()
        return call(name, arguments())
    }

    private fun newSession() = "kinuvo-${kotlin.random.Random.nextLong().toULong().toString(36)}"

    /** Performs [action]; returns why it wasn't done, if it wasn't. */
    private suspend fun act(d: Driver, action: ComputerAction, request: ComputerRequest): String? {
        val capture = request.capture
        val element = action.element?.let { n ->
            val current = last
            if (current == null || capture != null && capture != current.id) return "The screen changed since that capture; capture again"
            current.elements[n] ?: return "Element $n is no longer on screen; capture again"
        }
        if (!request.user) {
            // Approved for one app: the window under the pointer, or in front for keys and typing, must still be that app's.
            val x = action.x; val y = action.y
            val at = if (element == null && x != null && y != null) windowAt(d, x, y) else frontmost(d)
            if (request.app != null && at?.app != request.app) return "${at?.app ?: "Another app"} is there now, not ${request.app}; capture again"
            // Typing goes to the given element only, whose password status the Approval saw; focus could have moved anywhere.
            if (action.kind == ComputerActionKind.Type && element == null) return "Give the element to type into"
            if (action.kind == ComputerActionKind.Type && element?.password == true && !request.password)
                return "That's a password field; capture again, or ask the user to take over"
        }
        val point = action.x?.let { x -> action.y?.let { y -> x to y } }
        val target = when (action.kind) {
            ComputerActionKind.Tap, ComputerActionKind.DoubleTap, ComputerActionKind.LongPress -> if (element?.token == null) desktop(d, "click", point) else null
            ComputerActionKind.Type -> if (element?.token == null) desktop(d, "type_text") else null
            ComputerActionKind.Swipe -> desktop(d, "drag", point, action.toX?.let { x -> action.toY?.let { y -> x to y } })
            ComputerActionKind.Scroll -> if (element?.token == null) desktop(d, "scroll", point) else null
            ComputerActionKind.Key -> desktop(d, if ('+' in action.key.orEmpty()) "hotkey" else "press_key")
            else -> null
        }
        val result: JsonObject = when (action.kind) {
            ComputerActionKind.Tap, ComputerActionKind.DoubleTap, ComputerActionKind.LongPress -> d.tool("click") {
                if (element?.token != null) { put("element_token", element.token); element.pid?.let { put("pid", it) } }
                else { requireNotNull(point) { "Give an element or x and y" }; target?.forEach { (k, v) -> put(k, v) } }
                if (action.kind == ComputerActionKind.DoubleTap) put("count", 2)
                if (action.kind == ComputerActionKind.LongPress) put("button", "right")
            }
            ComputerActionKind.Type -> d.tool("type_text") {
                put("text", requireNotNull(action.text))
                if (element?.token != null) { put("element_token", element.token); element.pid?.let { put("pid", it) } } else target?.forEach { (k, v) -> put(k, v) }
            }
            ComputerActionKind.Swipe -> d.tool("drag") {
                requireNotNull(point); requireNotNull(action.toX); requireNotNull(action.toY)
                target?.forEach { (k, v) -> put(k, v) }
                action.durationMs?.let { put("duration_ms", it) }
            }
            ComputerActionKind.Scroll -> d.tool("scroll") {
                put("direction", action.direction ?: "down")
                when {
                    element?.token != null -> { put("element_token", element.token); element.pid?.let { put("pid", it) } }
                    else -> target?.forEach { (k, v) -> put(k, v) }
                }
            }
            ComputerActionKind.Key -> {
                val keys = requireNotNull(action.key).lowercase().split('+').map { KEYS[it.trim()] ?: it.trim() }
                if (keys.size == 1) d.tool("press_key") { put("key", keys.single()); target?.forEach { (k, v) -> put(k, v) } }
                else d.tool("hotkey") { put("keys", JsonArray(keys.map(::JsonPrimitive))); target?.forEach { (k, v) -> put(k, v) } }
            }
            ComputerActionKind.OpenApp -> {
                val app = requireNotNull(action.app)
                val launched = d.tool("launch_app") { put(if ('.' in app && ' ' !in app) "bundle_id" else "name", app) }
                launched["pid"]?.jsonPrimitive?.longOrNull?.let { pid -> d.tool("bring_to_front") { put("pid", pid) } }
                launched
            }
            ComputerActionKind.Wait -> { delay((action.durationMs ?: 1000).toLong()); JsonObject(emptyMap()) }
        }
        return result["error"]?.jsonPrimitive?.contentOrNull
    }

    private suspend fun desktop(d: Driver, tool: String, point: Pair<Int, Int>? = null, to: Pair<Int, Int>? = null): JsonObject {
        val supported = d.parameters(tool)
        val mode = when {
            supported == null -> if (tool in setOf("press_key", "hotkey", "type_text")) "scope" else "target"
            "target" in supported -> "target"
            "scope" in supported -> "scope"
            else -> "window"
        }
        val window = if (mode == "window") requireNotNull(point?.let { windowAt(d, it.first, it.second) } ?: frontmost(d)) {
            "No active desktop window; unlock the desktop before using it"
        } else null
        return buildJsonObject {
            when (mode) {
                "target" -> put("target", buildJsonObject { put("kind", "desktop"); put("display_id", "primary") })
                "scope" -> put("scope", "desktop")
                else -> { put("pid", requireNotNull(window).pid); put("window_id", window.windowId) }
            }
            fun coordinates(value: Pair<Int, Int>, prefix: String = "") {
                put("${prefix}x", (value.first - (window?.x ?: 0.0) * perPoint).roundToInt())
                put("${prefix}y", (value.second - (window?.y ?: 0.0) * perPoint).roundToInt())
            }
            point?.let { coordinates(it, if (tool == "drag") "from_" else "") }
            to?.let { coordinates(it, "to_") }
        }
    }

    private suspend fun capture(d: Driver, screenshot: Boolean, withElements: Boolean): ComputerScreen {
        val desktop = d.tool("get_desktop_state") { put("max_image_dimension", MAX_SIDE) }
        desktop["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
        val png = desktop["screenshot_png_b64"]?.jsonPrimitive?.contentOrNull ?: desktop["image"]?.jsonPrimitive?.contentOrNull
        val image = png?.let { runCatching { jpeg(Base64.decode(it)) }.getOrNull() }
        if (screenshot) requireNotNull(image) { "Desktop capture is unavailable; unlock the desktop and check Screen Recording access" }
        val pointsWide = desktop["screen_width"]?.jsonPrimitive?.doubleOrNull ?: 1.0
        val width = desktop["screenshot_width"]?.jsonPrimitive?.intOrNull ?: image?.second ?: pointsWide.roundToInt()
        val height = desktop["screenshot_height"]?.jsonPrimitive?.intOrNull ?: image?.third ?: 0
        perPoint = width / pointsWide
        val id = desktop["capture_id"]?.jsonPrimitive?.contentOrNull ?: "d${System.nanoTime()}"
        val front = frontmost(d)
        val elements = mutableListOf<ComputerElement>()
        val handles = mutableMapOf<Int, Element>()
        if (withElements && front != null) {
            val state = d.tool("get_window_state") {
                put("pid", front.pid); put("window_id", front.windowId); put("include_screenshot", false); put("max_elements", MAX_ELEMENTS)
            }
            val backing = desktop["scale_factor"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            val raw = state["elements"]?.jsonArray.orEmpty().map { it.jsonObject }
            // Frames are window-local; some drivers give them in backing pixels, which overflow the window in points.
            val divisor = if (raw.any { e -> e.frame()?.let { it[0] + it[2] > front.width * 1.1 } == true }) backing else 1.0
            raw.forEach { e ->
                val f = e.frame() ?: return@forEach
                if (f[2] <= 1 || f[3] <= 1) return@forEach
                val n = elements.size + 1
                handles[n] = Element(e["element_token"]?.jsonPrimitive?.contentOrNull, front.pid, e["role"]?.jsonPrimitive?.contentOrNull == "AXSecureTextField")
                val label = listOf("label", "value", "title", "description").firstNotNullOfOrNull { e[it]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
                val role = e["role"]?.jsonPrimitive?.contentOrNull?.removePrefix("AX") ?: "Element"
                val actions = e["actions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                elements += ComputerElement(n, role, label.take(200),
                    ((front.x + f[0] / divisor) * perPoint).roundToInt(), ((front.y + f[1] / divisor) * perPoint).roundToInt(),
                    (f[2] / divisor * perPoint).roundToInt(), (f[3] / divisor * perPoint).roundToInt(),
                    clickable = actions.any { it.contains("press", ignoreCase = true) }, editable = role in EDITABLE,
                    password = role == "SecureTextField", focused = e["focused"]?.jsonPrimitive?.booleanOrNull == true)
            }
            last = Capture(id, handles)
        } else if (withElements) last = Capture(id, emptyMap())
        return ComputerScreen(id, width, height, front?.app, front?.title, elements, if (screenshot) image?.first else null, platform)
    }

    private class Window(val pid: Long, val windowId: Long, val app: String, val title: String, val x: Double, val y: Double, val width: Double, val height: Double)

    /**
     * Windows of at least [minSize] points wide, the active app's first, largest first: cua-driver's z order doesn't follow
     * which app is in front, and it lists full-screen apps' windows as off screen. Others must be on screen.
     */
    private suspend fun ordered(d: Driver, minSize: Double): List<Window> {
        val active = d.tool("list_apps")["apps"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["active"]?.jsonPrimitive?.booleanOrNull == true }
            ?.get("pid")?.jsonPrimitive?.longOrNull
        val all = d.tool("list_windows")["windows"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            .filter { it["layer"]?.jsonPrimitive?.intOrNull == 0 && it["app_name"]?.jsonPrimitive?.contentOrNull !in IGNORED }
            .mapNotNull { w ->
                val b = w["bounds"]?.jsonObject ?: return@mapNotNull null
                val window = Window(w["pid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null, w["window_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    w["app_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), w["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    b["x"]?.jsonPrimitive?.doubleOrNull ?: 0.0, b["y"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    b["width"]?.jsonPrimitive?.doubleOrNull ?: 0.0, b["height"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                Triple(window, w["is_on_screen"]?.jsonPrimitive?.booleanOrNull == true, w["z_index"]?.jsonPrimitive?.intOrNull ?: 0)
            }.filter { it.first.width >= minSize && it.first.height >= minSize }
        val front = all.filter { it.first.pid == active }.sortedByDescending { it.first.width * it.first.height }.map { it.first }
        return front + all.filter { it.second && it.first.pid != active }.sortedByDescending { it.third }.map { it.first }
    }

    private suspend fun frontmost(d: Driver) = ordered(d, 100.0).firstOrNull()

    /** The front window at [x], [y] in the last screenshot's pixels. */
    private suspend fun windowAt(d: Driver, x: Int, y: Int): Window? {
        val px = x / perPoint; val py = y / perPoint
        return ordered(d, 1.0).firstOrNull { px >= it.x && px < it.x + it.width && py >= it.y && py < it.y + it.height }
    }

    private fun JsonObject.frame() = this["frame"]?.jsonObject?.let { f -> listOf("x", "y", "w", "h").map { f[it]?.jsonPrimitive?.doubleOrNull ?: return null } }

    private companion object {
        const val MAX_SIDE = 1600
        const val MAX_ELEMENTS = 300
        val KEYS = mapOf("enter" to "return", "back" to "escape", "esc" to "escape", "command" to "cmd", "control" to "ctrl", "alt" to "option")
        val EDITABLE = setOf("TextField", "TextArea", "SearchField", "ComboBox", "SecureTextField")
        val IGNORED = setOf("Cua Driver", "CursorUIViewService", "Window Server", "Dock")
    }
}

/** PNG to JPEG (base64, width, height): screenshots are sent to the model and to Live view many times. */
private fun jpeg(png: ByteArray): Triple<String, Int, Int> {
    val source = javax.imageio.ImageIO.read(png.inputStream()) ?: error("Unreadable screenshot")
    val rgb = java.awt.image.BufferedImage(source.width, source.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().apply { drawImage(source, 0, 0, java.awt.Color.WHITE, null); dispose() }
    val out = ByteArrayOutputStream()
    val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
    writer.output = javax.imageio.ImageIO.createImageOutputStream(out)
    writer.write(null, javax.imageio.IIOImage(rgb, null, null), writer.defaultWriteParam.apply {
        compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.8f
    })
    writer.dispose()
    return Triple(Base64.encode(out.toByteArray()), source.width, source.height)
}

/** cua-driver on PATH, in ~/.local/bin or in its app bundle. */
fun findDriver(): String? = (System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "cua-driver") } +
    File(System.getProperty("user.home"), ".local/bin/cua-driver") + File("/Applications/CuaDriver.app/Contents/MacOS/cua-driver"))
    .firstOrNull { it.canExecute() }?.path

/** `cua-driver mcp`, a stdio MCP server that forwards to its daemon, which holds the OS permissions. */
internal class McpDriver(path: String) : Driver {
    private val process = ProcessBuilder(path, "mcp").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val client = Client(Implementation("kinuvo", "0.1"))
    private var connected = false
    private var parameters: Map<String, Set<String>> = emptyMap()
    private var awake: Process? = null
    private var awakeAt = 0L

    override fun keepAwake(active: Boolean) {
        if (!active) { awake?.destroy(); awake = null; return }
        if (!System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) return
        val now = System.nanoTime()
        if (awake?.isAlive == true && now - awakeAt < 5_000_000_000L) return
        val next = ProcessBuilder("/usr/bin/caffeinate", "-diu", "-t", "15")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        awake?.destroy()
        awake = next
        awakeAt = now
    }

    private suspend fun connect() {
        if (!connected) {
            client.connect(StdioClientTransport(process.inputStream.asSource().buffered(), process.outputStream.asSink().buffered()))
            parameters = client.listTools().tools.associate { it.name to it.inputSchema.properties?.keys.orEmpty() }
            if (parameters["set_config"]?.contains("capture_scope") == true) {
                val configured = client.callTool("set_config", buildJsonObject { put("capture_scope", "desktop") })
                check(configured.isError != true) { configured.content.filterIsInstance<TextContent>().joinToString("\n") { it.text } }
            }
            connected = true
        }
    }

    override suspend fun parameters(tool: String): Set<String> { connect(); return parameters[tool].orEmpty() }

    override suspend fun call(tool: String, args: JsonObject): JsonObject {
        connect()
        val result = client.callTool(tool, args)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        val json = result.structuredContent ?: runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val image = result.content.filterIsInstance<ImageContent>().firstOrNull()?.data
        return JsonObject(json + listOfNotNull(image?.let { "image" to JsonPrimitive(it) }, ("error" to JsonPrimitive(text.ifBlank { "$tool failed" })).takeIf { result.isError == true }))
    }

    override fun close() { keepAwake(false); runCatching { process.destroy() } }
}
