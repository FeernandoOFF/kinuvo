package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.*
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.*

class ComputerToolsTest {
    private val fixture = RuntimeFixture()
    private val computers = Computers(SessionLog(fixture.store)) { Broker() }
    private val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf(ToolNames.COMPUTER)))
    private val requests = mutableListOf<ComputerRequest>()
    private val attachments = mutableListOf<ByteArray>()
    private var screen = ComputerScreen("fresh", 100, 60, "Notes", elements = listOf(
        ComputerElement(1, "Button", "Send message", 0, 0, 10, 10),
        ComputerElement(2, "TextField", "Secret", 0, 10, 20, 10, password = true),
    ), platform = "macOS", actions = ComputerActionKind.mac)
    private val run = object : ToolRun {
        override val tools = emptyList<ToolDescriptor>()
        override suspend fun call(name: String, args: String): SessionEntry.ToolResult = error("No tools")
        override suspend fun attach(name: String, bytes: ByteArray): Attachment {
            attachments += bytes
            return Attachment(name, name, "image/jpeg", bytes.size)
        }
    }

    private suspend fun tools(actions: Set<ComputerActionKind> = ComputerActionKind.mac): Map<String, Tool> {
        screen = screen.copy(actions = actions)
        computers.paired = { listOf(ToolDevice("mac", "Mac", setOf(DeviceToolKind.Computer))) }
        computers.connect("mac") { request ->
            requests += request
            when (request.action?.kind) {
                ComputerActionKind.ListApps -> ComputerResult(DeviceToolStatus.Ready, apps = listOf(
                    ComputerApp("Notes", "com.apple.Notes", true, true), ComputerApp("App Store", "com.apple.AppStore"),
                    ComputerApp("Installer", "com.apple.installer"), ComputerApp("Settings", "com.android.settings")))
                ComputerActionKind.ListWindows -> ComputerResult(DeviceToolStatus.Ready, windows = listOf(ComputerWindow(7, "Notes", "Draft", 0, 0, 100, 60)))
                ComputerActionKind.ClipboardRead -> ComputerResult(DeviceToolStatus.Ready, clipboard = "private text")
                else -> ComputerResult(DeviceToolStatus.Ready, screen = screen.copy(screenshot = screen.screenshot.takeIf { request.screenshot }))
            }
        }
        return computers.prepare(RunContext(agent, null), true).tools.associateBy { it.descriptor.name }
    }

    private fun args(action: String, values: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("action", action); values() }

    @Test fun activeUsageKeepsOriginalPrivateSessionAndPublicRoomAttribution() = runTest {
        tools()
        val context = RunContext(agent, "private", roomSessionId = "public")
        val prepared = computers.prepare(context, true).tools.associateBy { it.descriptor.name }
        assertNull(computers.list().single().agentId)
        prepared.getValue("computer_capture").execute(buildJsonObject {}, "capture", run)
        val active = computers.list().single()
        assertEquals(agent.id, active.agentId)
        assertEquals("private", active.sessionId)
        assertEquals("public", active.roomSessionId)
        computers.finished("unrelated")
        assertEquals(active.agentId, computers.list().single().agentId)
        computers.finished("private")
        val idle = computers.list().single()
        assertNull(idle.agentId)
        assertNull(idle.sessionId)
        assertNull(idle.roomSessionId)
        assertEquals(ComputerControl.Idle, idle.control)
        computers.close()
    }

    @Test fun capabilitiesFilterToolsAndActionsAndRejectUnsupportedComputers() = runTest {
        val tools = tools(ComputerActionKind.android)
        assertTrue("computer_apps" in tools)
        assertFalse("computer_windows" in tools)
        val act = tools.getValue("computer_act")
        val offered = act.descriptor.requiredParameters.single().type as ToolParameterType.Enum
        assertContains(offered.entries.toList(), "set_value")
        assertFalse("right_click" in offered.entries)
        assertTrue("verify_state" in computers.list().single().actions)
        val error = assertFailsWith<IllegalArgumentException> { act.execute(args("right_click") { put("element", 1) }, "call", run) }
        assertContains(error.message.orEmpty(), "not supported on Mac")
        assertTrue(requests.none { it.action?.kind == ComputerActionKind.RightClick })
        computers.close()
    }

    @Test fun offeredActionsAreTheUnionOfAvailableComputers() = runTest {
        tools(ComputerActionKind.android)
        computers.paired = { listOf("mac", "phone", "offline").map { ToolDevice(it, it, setOf(DeviceToolKind.Computer)) } }
        computers.connect("phone") { ComputerResult(DeviceToolStatus.Ready, screen = screen.copy(actions = setOf(ComputerActionKind.RightClick))) }
        val tools = computers.prepare(RunContext(agent, null), true).tools.associateBy { it.descriptor.name }
        val offered = tools.getValue("computer_act").descriptor.requiredParameters.single().type as ToolParameterType.Enum
        assertContains(offered.entries.toList(), "right_click")
        assertFalse("kill_app" in offered.entries)
        assertFailsWith<IllegalArgumentException> {
            tools.getValue("computer_act").execute(args("right_click") { put("computer", "mac"); put("element", 1) }, "call", run)
        }
        computers.close()
    }

    @Test fun appAndWindowReadsAreLowRiskAndMarkSensitiveApps() = runTest {
        val tools = tools()
        val apps = tools.getValue("computer_apps")
        assertEquals(Risk.Low, apps.risk)
        val json = apps.execute(buildJsonObject {})
        assertContains(json, "\"running\":null")
        val listed = ProtocolJson.decodeFromString<List<ComputerApp>>(json)
        assertFalse(listed.first().sensitive)
        assertTrue(listed.drop(1).all { it.sensitive })
        assertTrue(listed.first().running == true && listed.first().frontmost)
        val windows = tools.getValue("computer_windows")
        assertEquals(Risk.Low, windows.risk)
        assertContains(windows.execute(buildJsonObject {}), "Draft")
        val act = tools.getValue("computer_act")
        assertEquals(listOf("computer_act", "notes"), act.assess(args("set_window_frame") { put("window", 7) }).actions.single().words)
        computers.close()
    }

    @Test fun mandatoryActionsAlwaysAskEvenWithFullToleranceAndAppTrust() = runTest {
        val act = tools().getValue("computer_act")
        val tool = object : Tool by act {
            override val identity = ToolIdentity(PluginInstanceId("kinuvo.computer"), "computer_act")
        }
        fixture.rules = TrustRules(trusted = listOf(tool.identity.key, "${tool.identity.key} notes"))
        for (action in listOf("clipboard_read", "kill_app")) {
            val args = args(action) { put("app", "Notes") }
            val policy = ApprovalPolicy(agent, fixture.trust, Tolerance.Full)
            assertTrue(assertIs<Verdict.Ask>(policy.decide(tool, args.toString())).trustKeys.isEmpty())
        }
        assertContains(act.execute(args("clipboard_read"), "call", run), "private text")
        computers.close()
    }

    @Test fun setValueHasTheSamePasswordAndAppPolicyAsTyping() = runTest {
        val tools = tools()
        tools.getValue("computer_capture").execute(buildJsonObject {}, "call", run)
        val act = tools.getValue("computer_act")
        val args = args("set_value") { put("element", 2); put("text", "") }
        assertFalse(act.assess(args).actions.single().trustable)
        act.execute(args, "call", run)
        assertTrue(requests.last().password)
        assertEquals("Notes", requests.last().app)
        computers.close()
    }

    @Test fun verificationUsesFreshElementsWithoutAScreenshotAndCombinesPredicates() = runTest {
        val act = tools().getValue("computer_act")
        val checks = listOf(
            args("verify_state") { put("text", "send"); put("role", "button"); put("front_app", "Notes") } to true,
            args("verify_state") { put("text", "Send"); put("role", "TextField") } to false,
            args("verify_state") { put("front_app", "Mail") } to false,
            args("verify_state") { put("role", "Button") } to true,
        )
        for ((args, expected) in checks) {
            assertEquals(Risk.Low, act.assess(args).risk)
            assertContains(act.execute(args, "call", run), "matched=$expected")
            assertFalse(requests.last().screenshot)
            assertTrue(requests.last().elements)
            assertNull(requests.last().action)
        }
        assertTrue(attachments.isEmpty())
        assertFailsWith<IllegalArgumentException> { act.execute(args("tap") { put("element", 1) }, "call", run) }
        assertFailsWith<IllegalArgumentException> { act.execute(args("verify_state"), "call", run) }
        computers.close()
    }

    @Test fun zoomCropsTheLatestScreenshotAndKeepsItsCoordinateSpace() = runTest {
        val tools = tools()
        val image = BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.RED; fillRect(30, 20, 15, 10); dispose() }
        screen = screen.copy(screenshot = Base64.encode(ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()))
        tools.getValue("computer_capture").execute(buildJsonObject {}, "call", run)
        val act = tools.getValue("computer_act")
        val args = args("zoom") { put("x", 32); put("y", 22); put("width", 10); put("height", 5) }
        val before = requests.size
        assertEquals(Risk.Low, act.assess(args).risk)
        assertContains(act.execute(args, "call", run), "full capture")
        assertEquals(before, requests.size)
        val crop = ImageIO.read(attachments.last().inputStream())
        assertEquals(10 to 5, crop.width to crop.height)
        assertTrue(Color(crop.getRGB(4, 2)).red > 200)
        assertFailsWith<IllegalArgumentException> {
            act.execute(args("zoom") { put("x", 99); put("y", 0); put("width", 10); put("height", 5) }, "call", run)
        }
        tools.getValue("computer_capture").execute(buildJsonObject { put("screenshot", false) }, "call", run)
        assertFailsWith<IllegalArgumentException> { act.execute(args, "call", run) }
        computers.close()
    }
}
