package kinuvo.harness.cua

import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class DesktopComputerTest {
    private var legacy = false
    private var captureError: String? = null
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private val png = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()
    private val driver = object : Driver {
        override suspend fun parameters(tool: String) = if (legacy) when (tool) {
            "get_desktop_state" -> setOf("session")
            "list_apps", "list_windows" -> emptySet()
            "get_window_state" -> setOf("session", "pid", "window_id", "include_screenshot", "max_elements")
            "click" -> setOf("session", "scope", "pid", "window_id", "element_token", "x", "y", "count", "button")
            "hotkey" -> setOf("session", "pid", "window_id", "keys")
            "press_key" -> setOf("session", "pid", "window_id", "key")
            "drag" -> setOf("session", "pid", "window_id", "from_x", "from_y", "to_x", "to_y", "duration_ms")
            "scroll" -> setOf("session", "pid", "window_id", "element_token", "x", "y", "direction")
            else -> emptySet()
        } else null
        override suspend fun call(tool: String, args: JsonObject): JsonObject {
            parameters(tool)?.let { assertTrue(args.keys.all { key -> key in it }, "$tool received unsupported arguments: ${args.keys - it}") }
            calls += tool to args
            if (tool == "get_desktop_state" && captureError != null) return buildJsonObject { put("error", captureError) }
            return when (tool) {
                "get_desktop_state" -> json("""{"capture_id":"c1","screen_width":1000,"screen_height":500,"screenshot_width":500,"screenshot_height":250,"scale_factor":2.0,"screenshot_png_b64":"${Base64.encode(png)}"}""")
                "list_windows" -> json("""{"windows":[
                    {"app_name":"Cua Driver","pid":1,"window_id":1,"is_on_screen":true,"layer":0,"z_index":99,"title":"","bounds":{"x":0,"y":0,"width":1000,"height":500}},
                    {"app_name":"Calculator","pid":7,"window_id":70,"is_on_screen":true,"layer":0,"z_index":50,"title":"Calculator","bounds":{"x":100,"y":50,"width":200,"height":300}},
                    {"app_name":"Notes","pid":8,"window_id":80,"is_on_screen":false,"layer":0,"z_index":60,"title":"","bounds":{"x":0,"y":0,"width":400,"height":400}}]}""")
                "list_apps" -> json("""{"apps":[{"name":"Notes","pid":8,"active":false},{"name":"Calculator","pid":7,"active":true}]}""")
                "get_window_state" -> json("""{"elements":[{"element_index":0,"role":"AXButton","label":"7","frame":{"x":20,"y":40,"w":40,"h":20},"element_token":"t7","actions":["AXPress"]}]}""")
                else -> JsonObject(emptyMap())
            }
        }
    }
    private val computer = DesktopComputer { driver }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun saysHowToInstallTheDriverWhenItIsMissing() = runTest {
        val missing = DesktopComputer(connect = null)
        assertFalse(missing.ready)
        assertEquals(DeviceToolStatus.Unavailable, missing.perform(ComputerRequest()).status)
    }

    @Test
    fun capturesTheDesktopWithTheFrontmostWindowsElementsInScreenshotPixels() = runTest {
        val screen = computer.perform(ComputerRequest()).screen!!
        assertEquals(500 to 250, screen.width to screen.height)
        assertEquals("Calculator", screen.app)
        assertNotNull(screen.platform)
        val seven = screen.elements.single()
        // Window at (100, 50) points, element at (20, 40): half-size screenshot.
        assertEquals(listOf(60, 45, 20, 10), listOf(seven.x, seven.y, seven.width, seven.height))
        assertTrue(seven.clickable)
        assertNotNull(screen.screenshot)
        assertEquals(7L, calls.first { it.first == "get_window_state" }.second["pid"]?.jsonPrimitive?.long)
    }

    @Test
    fun tapsElementsByTokenAndPointsOnTheDesktop() = runTest {
        val capture = computer.perform(ComputerRequest()).screen!!.capture
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, element = 1), capture))
        assertEquals("t7", calls.last { it.first == "click" }.second["element_token"]?.jsonPrimitive?.content)
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, x = 10, y = 20)))
        val click = calls.last { it.first == "click" }.second
        assertEquals("desktop", click["target"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals(10, click["x"]?.jsonPrimitive?.int)
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Key, key = "cmd+c")))
        assertEquals(listOf("cmd", "c"), calls.last { it.first == "hotkey" }.second["keys"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(DeviceToolStatus.Denied, computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, element = 1), "old")).status)
    }

    @Test
    fun liveViewFramesSkipTheAccessibilityTree() = runTest {
        val screen = computer.perform(ComputerRequest(elements = false)).screen!!
        assertTrue(screen.elements.isEmpty())
        assertTrue(calls.none { it.first == "get_window_state" })
    }

    @Test
    fun usesTheInstalledDriversSchemaForCaptureAndInput() = runTest {
        legacy = true
        assertEquals(DeviceToolStatus.Ready, computer.perform(ComputerRequest()).status)
        assertEquals(setOf("session"), calls.first { it.first == "get_desktop_state" }.second.keys)
        assertTrue(calls.first { it.first == "list_apps" }.second.isEmpty())
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, x = 100, y = 50)))
        assertEquals("desktop", calls.last { it.first == "click" }.second["scope"]?.jsonPrimitive?.content)
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Key, key = "cmd+c")))
        assertEquals(7L, calls.last { it.first == "hotkey" }.second["pid"]?.jsonPrimitive?.long)
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Swipe, x = 100, y = 50, toX = 125, toY = 75)))
        val drag = calls.last { it.first == "drag" }.second
        assertEquals(70L, drag["window_id"]?.jsonPrimitive?.long)
        assertEquals(listOf(50, 25, 75, 50), listOf("from_x", "from_y", "to_x", "to_y").map { drag[it]?.jsonPrimitive?.int })
        computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Scroll, x = 100, y = 50)))
        assertEquals(50, calls.last { it.first == "scroll" }.second["x"]?.jsonPrimitive?.int)
    }

    @Test
    fun captureFailuresAreReportedInsteadOfReturningAnEmptyScreen() = runTest {
        captureError = "Display is locked"
        val result = computer.perform(ComputerRequest())
        assertEquals(DeviceToolStatus.Unavailable, result.status)
        assertContains(result.message.orEmpty(), "Display is locked")
        assertNull(result.screen)
    }

    @Test
    fun refusesWhenAnotherAppCameForwardSinceTheApproval() = runTest {
        val result = computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, x = 1, y = 1), app = "Notes"))
        assertEquals(DeviceToolStatus.Denied, result.status)
        assertTrue(calls.none { it.first == "click" })
    }

    @Test
    fun pointsMustLandInTheApprovedAppAndTypingNeedsAnElement() = runTest {
        computer.perform(ComputerRequest())
        // Calculator spans (100, 50) to (300, 350) points; screenshots are half size.
        assertEquals(DeviceToolStatus.Ready, computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, x = 100, y = 50), app = "Calculator")).status)
        assertEquals(DeviceToolStatus.Denied, computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Tap, x = 10, y = 10), app = "Calculator")).status)
        assertEquals(DeviceToolStatus.Denied, computer.perform(ComputerRequest(ComputerAction(ComputerActionKind.Type, text = "hi"), app = "Calculator")).status)
        assertTrue(calls.none { it.first == "type_text" })
    }
}
