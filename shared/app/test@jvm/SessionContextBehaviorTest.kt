package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.computer.*
import kinuvo.app.navigation.Route
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionContextBehaviorTest {
    @Test fun selectingMembersRestoresAlreadyCachedRoutinesWithoutWaitingForANewEvent() = fixture { owner, script ->
        script.routines = listOf(Routine("check", "a", "Check", "Check the plan", Trigger.Every(60), "UTC"))
        owner.routines.load("a").join()
        val vm = SessionContextViewModel(owner, roomScope)
        try {
            vm.selectAgent("a")
            vm.state.first { it.details != null && it.capabilities != null && it.routines != null }
            assertEquals(listOf("check"), vm.state.value.routines?.map { it.id })
            vm.selectAgent("b")
            vm.state.first { it.details?.info?.id == "b" && it.capabilities != null && it.routines != null }
            assertEquals(emptyList(), vm.state.value.routines)
            vm.selectAgent("a")
            vm.state.first { it.details?.info?.id == "a" && it.capabilities != null && it.routines != null }
            assertEquals(listOf("check"), vm.state.value.routines?.map { it.id })
        } finally { vm.dispose() }
    }

    @Test fun queuedExecutionSaveKeepsOriginalMemberAndCannotApplyToNewSelection() = fixture { owner, script ->
        val vm = SessionContextViewModel(owner, roomScope)
        try {
            vm.selectAgent("a")
            vm.state.first { it.details?.info?.id == "a" && it.capabilities != null }
            script.holdConfigure = true
            val first = vm.saveExecution(SessionSettings(exec = "host", workspace = listOf("/a")))
            val request = script.heldConfigure.receive()
            val queued = vm.saveExecution(SessionSettings(exec = "container", workspace = listOf("/queued-a")))
            vm.selectAgent("b")
            vm.state.first { it.details?.info?.id == "b" && it.capabilities != null }
            script.finishConfigure(request)
            first.join(); queued.join()
            assertEquals(listOf("a"), script.configured.map { it.agentId })
            assertEquals("b", vm.state.value.details?.info?.id)
            assertEquals(SessionSettings(), vm.state.value.settings)
        } finally { vm.dispose() }
    }

    @Test fun oldDetailsCannotReplaceMemberAfterAtoBtoA() = fixture { owner, script ->
        val vm = SessionContextViewModel(owner, roomScope)
        try {
            script.holdAgent = "a"
            vm.selectAgent("a")
            val old = script.heldDetails.receive()
            vm.selectAgent("b")
            vm.state.first { it.details?.info?.id == "b" }
            script.holdAgent = null
            script.label = "latest"
            vm.selectAgent("a")
            vm.state.first { it.details?.info?.name == "latest-a" }
            script.reply(old, Api.Agent, script.details("a", "stale"))
            yield()
            assertEquals("latest-a", vm.state.value.details?.info?.name)
        } finally { vm.dispose() }
    }

    @Test fun oldRefreshCannotRestoreSettingsFromBeforeSave() = fixture { owner, script ->
        val vm = SessionContextViewModel(owner, roomScope)
        try {
            vm.selectAgent("a")
            vm.state.first { it.details != null && it.capabilities != null }
            script.holdView = true
            val old = vm.refreshExecution()
            val stale = script.heldViews.receive()
            script.holdView = false
            val saved = SessionSettings(exec = "host", workspace = listOf("/new"))
            vm.saveExecution(saved).join()
            script.reply(stale, Api.RoomSession, RoomView(script.session.copy(memberSettings = emptyMap())))
            old.join()
            assertEquals(saved, vm.state.value.settings)
        } finally { vm.dispose() }
    }

    @Test fun backgroundArtifactSuccessCannotClearExecutionFailure() = fixture { owner, script ->
        script.holdArtifacts = true
        val vm = SessionContextViewModel(owner, roomScope)
        try {
            vm.selectAgent("a")
            vm.state.first { it.details != null && it.capabilities != null }
            val artifact = script.heldArtifacts.receive()
            script.failConfigure = true
            vm.saveExecution(SessionSettings(exec = "host", workspace = listOf("/work"))).join()
            val error = vm.state.value.error
            assertNotNull(error)
            script.reply(artifact, Api.Artifacts, emptyList())
            vm.state.first { it.artifacts != null }
            assertEquals(error, vm.state.value.error)
        } finally { vm.dispose() }
    }

    @Test fun computerStreamsStayIdleAndDisposeWithoutImplicitHandBack() = fixture { owner, script ->
        val computers = ComputersViewModel(requireNotNull(owner.client), Route.Computers(), owner, autoWatch = false)
        computers.state.first { it.computers != null }
        assertTrue(script.watches.isEmpty())
        computers.watchActive("computer")
        script.watchEvents.receive().also { assertTrue(it.watch) }
        computers.watchActive(null)
        script.watchEvents.receive().also { assertFalse(it.watch) }
        computers.select("computer")
        computers.takeOver()
        computers.state.first { it.control == ComputerControl.User }
        computers.watchActive("computer")
        script.watchEvents.receive().also { assertTrue(it.watch) }
        computers.dispose()
        script.watchEvents.receive().also { assertFalse(it.watch) }
        assertEquals(listOf(true), script.takeovers)
        assertEquals(ComputerControl.User, script.computer.control)
    }

    @Test fun attributionChangeCannotReuseThePreviousSessionsScreenshot() = fixture { owner, script ->
        val computers = ComputersViewModel(requireNotNull(owner.client), Route.Computers(), owner, autoWatch = false)
        try {
            computers.state.first { it.computers != null }
            computers.watchActive("computer")
            script.watchEvents.receive()
            script.event(Api.ComputerFrameEvent, ComputerFrame(script.computer, ComputerScreen("capture-a", 10, 10, screenshot = "a")))
            computers.state.first { it.frame?.screen != null }
            val next = script.computer.copy(agentId = "b", sessionId = "private-b")
            script.event(Api.ComputersChanged, listOf(next))
            computers.state.first { it.computer?.agentId == "b" }
            assertNull(computers.state.value.frame)
            script.event(Api.ComputerFrameEvent, ComputerFrame(next))
            computers.state.first { it.frame?.computer?.agentId == "b" }
            assertNull(computers.state.value.frame?.screen)
        } finally { computers.dispose() }
    }

    private val roomScope = SessionContextScope(roomId = "room", roomSessionId = "public", memberAgentIds = listOf("a", "b"))

    private fun fixture(block: suspend (ConnectedServer, Script) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try { withContext(main) { withTimeout(15_000) {
                val prefs = MemoryPreferences()
                val reader = object : DeviceReader {
                    override val kinds = emptyList<String>()
                    override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                    override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                }
                val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(prefs, reader))
                val script = Script()
                val client = KinuvoClient("local:test", "test", connection = { script })
                owner.open(client, "local:test")
                try { client.connect(); block(owner, script) } finally { owner.close() }
            } } } finally { Dispatchers.resetMain() }
        }
    }

    private class Script : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val caps = ServerCapabilities("test", features = setOf(ServerFeature.Computer))
        var session = RoomSession("public", "room")
        var routines = emptyList<Routine>()
        var label = "initial"
        var holdAgent: String? = null
        var holdView = false
        var holdConfigure = false
        var failConfigure = false
        var holdArtifacts = false
        val heldDetails = Channel<Frame.Request>(Channel.UNLIMITED)
        val heldViews = Channel<Frame.Request>(Channel.UNLIMITED)
        val heldConfigure = Channel<Frame.Request>(Channel.UNLIMITED)
        val heldArtifacts = Channel<Frame.Request>(Channel.UNLIMITED)
        val configured = mutableListOf<ConfigureRoomSessionParams>()
        val watches = mutableListOf<WatchComputerParams>()
        val watchEvents = Channel<WatchComputerParams>(Channel.UNLIMITED)
        val takeovers = mutableListOf<Boolean>()
        var computer = ComputerInfo("computer", "Mac", "macOS", true, ComputerControl.Agent, agentId = "a", sessionId = "private", roomSessionId = "public")
        fun details(id: String, title: String = label) = AgentDetails(AgentInfo(id, "$title-$id", "host"), AgentSettings("model", "medium", "default", "host", listOf("/work")), "", "")
        override suspend fun send(frame: Frame) {
            val req = frame as Frame.Request
            when (req.method) {
                Api.Connect.name -> reply(req, Api.Connect, ConnectResult("token", "Context", capabilities = caps, management = true))
                Api.Presence.name -> reply(req, Api.Presence, Unit)
                Api.CatchUp.name -> reply(req, Api.CatchUp, Unit)
                Api.Agents.name -> reply(req, Api.Agents, listOf(details("a").info, details("b").info))
                Api.Agent.name -> {
                    val id = ProtocolJson.decodeFromJsonElement(Api.Agent.params, req.params).agentId
                    if (id == holdAgent) heldDetails.send(req) else reply(req, Api.Agent, details(id))
                }
                Api.Artifacts.name -> if (holdArtifacts) heldArtifacts.send(req) else reply(req, Api.Artifacts, emptyList())
                Api.Routines.name -> {
                    val agent = ProtocolJson.decodeFromJsonElement(Api.Routines.params, req.params).agentId
                    reply(req, Api.Routines, routines.filter { it.agentId == agent })
                }
                Api.RoomSession.name -> if (holdView) heldViews.send(req) else reply(req, Api.RoomSession, RoomView(session, members = listOf("a", "b").associateWith { SessionCapabilities(server = caps) }))
                Api.ConfigureRoomSession.name -> {
                    configured += ProtocolJson.decodeFromJsonElement(Api.ConfigureRoomSession.params, req.params)
                    if (failConfigure) frames.send(Frame.Response(req.id, false, error = "Execution save failed"))
                    else if (holdConfigure) heldConfigure.send(req) else finishConfigure(req)
                }
                Api.Computers.name -> reply(req, Api.Computers, listOf(computer))
                Api.WatchComputer.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.WatchComputer.params, req.params)
                    watches += params; watchEvents.send(params)
                    reply(req, Api.WatchComputer, Unit)
                }
                Api.Takeover.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.Takeover.params, req.params)
                    takeovers += params.take
                    computer = computer.copy(control = if (params.take) ComputerControl.User else ComputerControl.Agent)
                    reply(req, Api.Takeover, ComputerFrame(computer))
                }
                else -> frames.send(Frame.Response(req.id, false, error = "Unsupported test method: ${req.method}"))
            }
        }
        suspend fun finishConfigure(req: Frame.Request) {
            val params = ProtocolJson.decodeFromJsonElement(Api.ConfigureRoomSession.params, req.params)
            session = session.copy(memberSettings = session.memberSettings + (params.agentId to params.settings))
            reply(req, Api.ConfigureRoomSession, session)
        }
        suspend fun <P, R> reply(req: Frame.Request, method: Method<P, R>, result: R) = frames.send(Frame.Response(req.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        suspend fun <T> event(type: EventType<T>, payload: T) = frames.send(Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), 0))
        override suspend fun close() = Unit
    }
}
