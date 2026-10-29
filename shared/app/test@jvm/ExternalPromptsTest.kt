package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModelStore
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import kotlin.test.*

/** An external Agent's pending requests reach the Session screen, from a reopened Session and as they arrive. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPromptsTest {
    private val wren = AgentInfo("wren", "Wren", "external", executionKind = ExecutionKind.Acp, connectionId = "codex")
    private val urlApproval = ExecutionRequest("open", ExecutionRequestKind.Approval, Json.parseToJsonElement("""
        {"title":"MCP server requests to open a URL","subject":{"type":"tool_call","toolCall":{"toolCallId":"t","kind":"fetch","status":"pending",
        "content":[{"type":"content","content":{"type":"text","text":"Reconnect Slack to restore access."}}],
        "rawInput":{"serverName":"codex_apps","description":"Reconnect Slack to restore access.","url":"https://user:pw@chatgpt.com/connect?token=secret"}}}}
    """), listOf(ExternalApprovalOption("accept", "Allow", "allow_once"), ExternalApprovalOption("decline", "Deny", "reject_once")))

    @Test fun reopenedSessionShowsItsPendingApproval() = withServer(ExecutionSnapshot("s1", ExecutionPhase.Running, pending = listOf(urlApproval))) { owner, _ ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("wren", "s1"))
        ViewModelStore().run {
            put("chat", vm)
            try {
                val prompt = vm.state.first { it.prompts.isNotEmpty() }.prompts.single()
                assertIs<ExternalPrompt.Approval>(prompt)
                assertEquals("MCP server requests to open a URL", prompt.title)
            } finally { clearAndJoin() }
        }
    }

    @Test fun delayedSnapshotDoesNotOverwriteNewerApprovalEvent() = withServer(ExecutionSnapshot("s1", ExecutionPhase.Lost)) { owner, script ->
        script.holdSnapshots = true
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("wren", "s1"))
        ViewModelStore().run {
            put("chat", vm)
            try {
                script.snapshots.receive()
                script.snapshot = ExecutionSnapshot("s1", ExecutionPhase.RequiresAction, pending = listOf(urlApproval))
                script.event(Api.ExecutionChanged, script.snapshot)
                vm.state.first { it.prompts.isNotEmpty() }
                script.releaseSnapshots()
                delay(100)
                assertEquals("open", vm.state.value.prompts.single().id)
                assertEquals(ExecutionPhase.RequiresAction, vm.state.value.phase)
            } finally { clearAndJoin() }
        }
    }

    @Test fun arrivingApprovalShowsInOpenSession() = withServer(ExecutionSnapshot("s1", ExecutionPhase.Running)) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("wren", "s1"))
        ViewModelStore().run {
            put("chat", vm)
            try {
                script.snapshots.receive()
                script.event(Api.ExecutionChanged, ExecutionSnapshot("s1", ExecutionPhase.Running, pending = listOf(urlApproval)))
                assertEquals("open", vm.state.first { it.prompts.isNotEmpty() }.prompts.single().id)
            } finally { clearAndJoin() }
        }
    }

    @Test fun resumeAndCloseUseExecutionMethodsAndKeepTheSelectedSession() = withServer(ExecutionSnapshot("s1", ExecutionPhase.Lost)) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("wren", "s1"))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { it.phase == ExecutionPhase.Lost }
                vm.resumeExternal()
                vm.state.first { it.phase == ExecutionPhase.Idle && !it.resuming }
                assertEquals("s1", vm.state.value.route.sessionId)
                assertEquals(listOf(Api.ResumeExecution.name), script.actions)
                vm.closeExternal()
                vm.state.first { it.phase == ExecutionPhase.Lost }
                assertEquals(listOf(Api.ResumeExecution.name, Api.CloseExecution.name), script.actions)
                assertEquals("s1", vm.state.value.route.sessionId)
            } finally { clearAndJoin() }
        }
    }

    @Test fun unsupportedResumeShowsTheServersPreciseReason() = withServer(ExecutionSnapshot("s1", ExecutionPhase.Lost)) { owner, script ->
        script.resumeError = "This ACP harness does not support session/load"
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("wren", "s1"))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { it.phase == ExecutionPhase.Lost }
                vm.resumeExternal()
                val failed = vm.state.first { it.errors.isNotEmpty() && !it.resuming }
                assertTrue(failed.errors.any { it.contains(requireNotNull(script.resumeError)) })
                assertEquals(ExecutionPhase.Lost, failed.phase)
            } finally { clearAndJoin() }
        }
    }

    private fun withServer(snapshot: ExecutionSnapshot, block: suspend (ConnectedServer, Script) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                withContext(main) {
                    withTimeout(15_000) {
                        val reader = object : DeviceReader {
                            override val kinds = emptyList<String>()
                            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                        }
                        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
                        val script = Script(snapshot)
                        val client = KinuvoClient("local:test", "test", connection = { script })
                        owner.open(client, "local:test")
                        client.connect()
                        owner.activate()
                        try { block(owner, script) } finally {
                            val job = owner.scope.coroutineContext.job
                            owner.close()
                            job.join()
                        }
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private inner class Script(var snapshot: ExecutionSnapshot) : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val actions = mutableListOf<String>()
        var resumeError: String? = null
        val snapshots = Channel<Unit>(Channel.UNLIMITED)
        var holdSnapshots = false
        private val heldSnapshots = mutableListOf<Pair<Frame.Request, ExecutionSnapshot>>()
        suspend fun releaseSnapshots() {
            holdSnapshots = false
            heldSnapshots.toList().forEach { (request, captured) -> reply(request, Api.ExecutionSnapshot, captured) }
            heldSnapshots.clear()
        }
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "test", capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(wren))
                Api.ExecutionCatalog.name -> reply(request, Api.ExecutionCatalog, ExecutionCatalog(emptyList(), listOf(AgentRegistration("wren", "Wren", "codex"))))
                Api.ExecutionSnapshot.name -> { if (holdSnapshots) heldSnapshots += request to snapshot else reply(request, Api.ExecutionSnapshot, snapshot); snapshots.send(Unit) }
                Api.ResumeExecution.name -> {
                    actions += request.method
                    if (resumeError != null) frames.send(Frame.Response(request.id, false, error = resumeError))
                    else { snapshot = snapshot.copy(phase = ExecutionPhase.Idle); reply(request, Api.ResumeExecution, snapshot) }
                }
                Api.CloseExecution.name -> {
                    actions += request.method
                    snapshot = snapshot.copy(phase = ExecutionPhase.Lost, stopReason = "session_closed")
                    reply(request, Api.CloseExecution, Unit)
                }
                Api.Session.name -> reply(request, Api.Session, SessionInfo("s1", "wren", "Slack"))
                Api.Capabilities.name -> reply(request, Api.Capabilities, SessionCapabilities(0, ServerCapabilities("test")))
                Api.CatchUp.name -> reply(request, Api.CatchUp, Unit)
                else -> frames.send(Frame.Response(request.id, false, error = "Unsupported in test: ${request.method}"))
            }
        }
        suspend fun <T> event(type: EventType<T>, payload: T) = frames.send(Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), 1))
        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }
        override suspend fun close() = Unit
    }
}
