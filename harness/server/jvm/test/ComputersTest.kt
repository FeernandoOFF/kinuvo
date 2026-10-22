package kinuvo.harness.server

import kinuvo.harness.testing.*

import kinuvo.harness.core.RunContext
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.ToolRun
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*

class ComputersTest {
    private val home = Files.createTempDirectory("computers").toFile()
    private val harness = Harness(home = Path(home.path), modelFactory = { scriptedModel { _, _ -> kotlinx.coroutines.flow.emptyFlow() } })
    private val phone = ToolDevice("phone", "Pixel", setOf(DeviceToolKind.Computer))
    private val attached = mutableListOf<String>()
    private val run = object : ToolRun {
        override val tools = emptyList<ai.koog.agents.core.tools.ToolDescriptor>()
        override suspend fun call(name: String, args: String) = error("No tools")
        override suspend fun attach(name: String, bytes: ByteArray): Attachment { attached += name; return Attachment(name, name, "image/jpeg", bytes.size) }
    }
    private val screen = ComputerScreen("c1", 540, 1200, app = "com.example.chat", elements = listOf(
        ComputerElement(1, "Button", "Send", 400, 1100, 100, 60, clickable = true),
        ComputerElement(2, "EditText", "Password", 20, 1100, 360, 60, editable = true, password = true, focused = true),
    ), screenshot = Base64.encode(byteArrayOf(1, 2, 3)), platform = "Android")

    @AfterTest
    fun cleanup() { home.deleteRecursively() }

    private suspend fun tools(): Map<String, kinuvo.harness.core.tools.Tool> {
        val default = harness.store.agents().single()
        harness.store.saveConfig(default, default.config.copy(tools = default.config.tools + ToolNames.COMPUTER))
        val agent = harness.store.agents().single()
        harness.computers.paired = { listOf(phone) }
        return harness.computers.prepare(RunContext(agent, harness.newSession(agent).id), activate = true).tools.associateBy { it.descriptor.name }
    }

    @Test
    fun captureShowsElementsAndScreenshotAndActsOnThatCapture() = runBlocking {
        val tools = tools()
        val requests = mutableListOf<ComputerRequest>()
        harness.computers.connect(phone.id) { requests += it; ComputerResult(DeviceToolStatus.Ready, screen = screen) }
        val captured = tools.getValue("computer_capture").execute(JsonObject(emptyMap()), "call", run)
        assertContains(captured, "[1] Button \"Send\" clickable @450,1130")
        assertContains(captured, "app com.example.chat")
        assertEquals(listOf("screen-c1.jpg"), attached)
        tools.getValue("computer_act").execute(buildJsonObject { put("action", "tap"); put("element", 1) }, "call", run)
        assertEquals(ComputerAction(ComputerActionKind.Tap, element = 1), requests.last().action)
        assertEquals("c1", requests.last().capture)
    }

    @Test
    fun everyAppAsksUntilTrustedAndPasswordsAndSystemScreensAlwaysAsk() = runBlocking {
        val tools = tools()
        harness.computers.connect(phone.id) { ComputerResult(DeviceToolStatus.Ready, screen = screen) }
        tools.getValue("computer_capture").execute(JsonObject(emptyMap()), "call", run)
        val act = tools.getValue("computer_act")
        val password = act.assess(buildJsonObject { put("action", "type"); put("text", "hunter2") })
        assertEquals(Risk.High, password.risk)
        assertFalse(password.actions.single().trustable)
        val tap = act.assess(buildJsonObject { put("action", "tap"); put("element", 1) }).actions.single()
        assertEquals(Risk.High to listOf("computer_act", "com.example.chat"), tap.risk to tap.words)
        assertTrue(tap.trustable && tap.exact)
        assertEquals(listOf("computer_act", "google_maps"), act.assess(buildJsonObject { put("action", "open_app"); put("app", "Google Maps") }).actions.single().words)
        assertFalse(act.assess(buildJsonObject { put("action", "open_app"); put("app", "Settings") }).actions.single().trustable)
        assertEquals(Risk.Low, act.assess(buildJsonObject { put("action", "key"); put("key", "home") }).risk)
    }

    @Test
    fun preparingRunsAsksAConnectedComputerOnce() = runBlocking {
        tools()
        var asked = 0
        harness.computers.connect(phone.id) { asked++; ComputerResult(DeviceToolStatus.Ready, screen = screen) }
        repeat(3) { tools() }
        assertEquals(1, asked)
        harness.computers.connect(phone.id) { asked++; ComputerResult(DeviceToolStatus.Ready, screen = screen) }
        tools()
        assertEquals(2, asked)
    }

    @Test
    fun offlineComputersReportItAndMissingArgumentsFailEarly() = runBlocking {
        val tools = tools()
        assertContains(tools.getValue("computer_capture").execute(JsonObject(emptyMap()), "call", run), "Unavailable")
        assertFailsWith<IllegalArgumentException> { tools.getValue("computer_act").execute(buildJsonObject { put("action", "type") }, "call", run) }
        assertFalse(harness.computers.list().single().available)
    }

    @Test
    fun aPairedPhoneIsControlledOverItsConnectionInTheBackground() = runBlocking {
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        val client = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "Pixel", pairingCode = server.auth.currentPairingCode(),
            deviceTools = { setOf(DeviceToolKind.Computer) }, computer = { ComputerResult(DeviceToolStatus.Ready, screen = screen) })
        try {
            withTimeout(10_000) {
                client.connect()
                client.setActive(false)
                val default = harness.store.agents().single()
                harness.store.saveConfig(default, default.config.copy(tools = default.config.tools + ToolNames.COMPUTER))
                val agent = harness.store.agents().single()
                val tool = harness.computers.prepare(RunContext(agent, harness.newSession(agent).id), activate = true).tools.single { it.descriptor.name == "computer_capture" }
                assertTrue(harness.computers.list().single().available)
                assertContains(tool.execute(JsonObject(emptyMap()), "call", run), "Pixel (Android)")
            }
        } finally { client.close(); server.stop() }
    }

    @Test
    fun agentsWaitDuringATakeoverAndContinueWhenHandedBack() = runBlocking {
        withTimeout(5_000) {
            val tools = tools()
            harness.computers.connect(phone.id) { ComputerResult(DeviceToolStatus.Ready, screen = screen) }
            harness.computers.takeover(phone.id, take = true)
            assertEquals(ComputerControl.User, harness.computers.list().single().control)
            val capture = async { tools.getValue("computer_capture").execute(JsonObject(emptyMap()), "call", run) }
            delay(200)
            assertFalse(capture.isCompleted)
            harness.computers.takeover(phone.id, take = false)
            assertContains(capture.await(), "[1] Button")
        }
    }

    @Test
    fun requestTakeoverAsksTheUserAndReturnsTheirNote(): Unit = runBlocking {
        withTimeout(5_000) {
            val tools = tools()
            harness.computers.connect(phone.id) { ComputerResult(DeviceToolStatus.Ready, screen = screen) }
            val asked = CompletableDeferred<AgentEvent.QuestionsAsked>()
            harness.log.onEvent { _, _, event -> if (event is AgentEvent.QuestionsAsked) asked.complete(event) }
            val result = async { tools.getValue("request_takeover").execute(buildJsonObject { put("reason", "Sign in") }, "call") }
            val form = asked.await()
            assertContains(form.title.orEmpty(), "Sign in")
            assertEquals("Sign in", harness.computers.list().single().takeoverRequest)
            harness.questions.respond(form.questionsId, mapOf("note" to listOf("Signed in")))
            assertContains(result.await(), "noted: Signed in")
            assertNull(harness.computers.list().single().takeoverRequest)
        }
    }

    @Test
    fun watchersGetFramesWithoutElementsAndTheUsersInputTakesOver(): Unit = runBlocking {
        withTimeout(5_000) {
            tools()
            val requests = mutableListOf<ComputerRequest>()
            harness.computers.connect(phone.id) { requests += it; ComputerResult(DeviceToolStatus.Ready, screen = screen) }
            val frames = kotlinx.coroutines.channels.Channel<ComputerFrame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val watcher: (ComputerFrame) -> Unit = { frames.trySend(it) }
            harness.computers.watch(phone.id, watcher)
            val frame = frames.receive()
            assertTrue(frame.screen!!.elements.isEmpty())
            assertFalse(requests.first().elements)
            val input = harness.computers.input(phone.id, ComputerAction(ComputerActionKind.Tap, x = 10, y = 20))
            assertEquals(ComputerControl.User, input.computer.control)
            assertTrue(requests.last().user)
            harness.computers.unwatch(phone.id, watcher)
        }
    }

    @Test
    fun eachRunActsOnItsOwnCaptureAndApprovedApp() = runBlocking {
        val first = tools()
        val second = tools()
        var captures = 0
        val requests = mutableListOf<ComputerRequest>()
        harness.computers.connect(phone.id) { requests += it; ComputerResult(DeviceToolStatus.Ready, screen = screen.copy(capture = "c${++captures}")) }
        first.getValue("computer_capture").execute(JsonObject(emptyMap()), "a", run)
        second.getValue("computer_capture").execute(JsonObject(emptyMap()), "b", run)
        first.getValue("computer_act").execute(buildJsonObject { put("action", "tap"); put("element", 1) }, "a", run)
        assertEquals("c1", requests.last().capture)
        assertEquals("com.example.chat", requests.last().app)
        first.getValue("computer_act").execute(buildJsonObject { put("action", "key"); put("key", "home") }, "a", run)
        assertNull(requests.last().app)
    }

    @Test
    fun anActionQueuedBehindAnotherWaitsForATakeoverStartedMeanwhile(): Unit = runBlocking {
        withTimeout(5_000) {
            val tools = tools()
            val busy = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val actions = mutableListOf<ComputerAction?>()
            harness.computers.connect(phone.id) {
                actions += it.action
                if (actions.size == 2) { busy.complete(Unit); release.await() }
                ComputerResult(DeviceToolStatus.Ready, screen = screen)
            }
            tools.getValue("computer_capture").execute(JsonObject(emptyMap()), "a", run)
            val slow = async { tools.getValue("computer_act").execute(buildJsonObject { put("action", "wait") }, "a", run) }
            busy.await()
            val queued = async { tools.getValue("computer_act").execute(buildJsonObject { put("action", "tap"); put("element", 1) }, "b", run) }
            delay(100)
            val taking = async { harness.computers.takeover(phone.id, take = true) }
            while (harness.computers.list().single().control != ComputerControl.User) delay(20)
            release.complete(Unit)
            taking.await()
            slow.await()
            delay(200)
            assertTrue(actions.none { it?.kind == ComputerActionKind.Tap })
            harness.computers.takeover(phone.id, take = false)
            assertContains(queued.await(), "capture again")
        }
    }

    @Test
    fun aTakeoverStartedOnThePhoneEndsWhenItIsHandedBackThere(): Unit = runBlocking {
        withTimeout(10_000) {
            val tools = tools()
            var userOnPhone = false
            harness.computers.connect(phone.id) { ComputerResult(DeviceToolStatus.Ready, screen = screen, userControl = userOnPhone) }
            val result = async { tools.getValue("request_takeover").execute(buildJsonObject { put("reason", "Sign in") }, "call") }
            userOnPhone = true
            withTimeout(5_000) { while (harness.computers.list().single().control != ComputerControl.User) delay(100) }
            userOnPhone = false
            assertContains(result.await(), "finished")
            assertEquals(ComputerControl.Idle, harness.computers.list().single().control)
        }
    }

    @Test
    fun theDesktopAppOffersItsOwnScreenToTheServerItEmbeds(): Unit = runBlocking {
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        val desktop = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "Mac", token = server.auth.sessionToken(),
            deviceTools = { setOf(DeviceToolKind.Computer) }, computer = { ComputerResult(DeviceToolStatus.Ready, screen = screen.copy(platform = "macOS")) })
        try {
            withTimeout(10_000) {
                desktop.connect()
                val mac = harness.computers.list().single()
                assertEquals("Mac" to "Device", mac.name to mac.platform)
                harness.computers.takeover(mac.id, take = false)
                assertEquals("macOS", harness.computers.list().single().platform)
            }
        } finally { desktop.close(); server.stop() }
    }

    @Test
    fun reconnectingDesktopAppStaysOneComputer() {
        val auth = DeviceAuth(java.io.File(home, "devices.json"))
        repeat(2) {
            val token = auth.sessionToken()
            auth.authenticate("Mac", token, null)
            auth.setTools(token, setOf(DeviceToolKind.Computer))
        }
        assertEquals(listOf("Mac"), auth.toolDevices().map { it.name })
    }
}
