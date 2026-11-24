package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModelStore
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.mcp.*
import kinuvo.protocol.model.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import kotlin.test.*

/** [SessionViewModel.connect]/[SessionViewModel.completeConnect]/[SessionViewModel.skipConnect] driving an `mcp_login` Connect card. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectCardTest {
    private val main = AgentInfo(AgentId("main"), "Main", "native")
    private val interaction = PendingInteraction.Connect(InteractionId("x1"), ToolCallId("c1"), AgentId("main"), "linear", site = "linear.app")

    @Test fun connectRunsOAuthAndAnswersConnected() = withServer(LiveState(pending = listOf(interaction))) { owner, script, browser ->
        browser.callback = "http://localhost:33418/callback?code=ok"
        val vm = SessionViewModel(owner.sessions, Route.Chat(AgentId("main"), SessionId("s1")), browser = browser)
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                vm.state.first { it.pending.isNotEmpty() }
                vm.connect(interaction)
                vm.state.first { it.connecting.isEmpty() && script.respondedConnected.isNotEmpty() }
                assertEquals(listOf(true), script.respondedConnected)
                assertEquals("linear", script.logins.single().name)
                assertEquals("http://localhost:33418/callback?code=ok", script.completions.single())
                assertTrue(browser.opened != null)
            } finally { clearAndJoin() }
        }
    }

    @Test fun awaitingPasteThenCompleteConnectFinishes() = withServer(LiveState(pending = listOf(interaction))) { owner, script, browser ->
        browser.callback = null
        val vm = SessionViewModel(owner.sessions, Route.Chat(AgentId("main"), SessionId("s1")), browser = browser)
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                vm.state.first { it.pending.isNotEmpty() }
                vm.connect(interaction)
                vm.state.first { it.connecting[interaction.id] is ConnectState.AwaitingPaste }
                vm.completeConnect(interaction, "localhost:33418/callback?code=pasted")
                vm.state.first { it.connecting.isEmpty() }
                assertEquals(listOf(true), script.respondedConnected)
                assertEquals("http://localhost:33418/callback?code=pasted", script.completions.single())
            } finally { clearAndJoin() }
        }
    }

    @Test fun skipDeclinesWithoutAnyOAuthCall() = withServer(LiveState(pending = listOf(interaction))) { owner, script, browser ->
        val vm = SessionViewModel(owner.sessions, Route.Chat(AgentId("main"), SessionId("s1")), browser = browser)
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                vm.state.first { it.pending.isNotEmpty() }
                vm.skipConnect(interaction)
                withTimeout(5_000) { while (script.respondedConnected.isEmpty() && vm.state.value.errors.isEmpty()) delay(10) }
                assertEquals(listOf(false), script.respondedConnected)
                assertTrue(script.logins.isEmpty())
            } finally { clearAndJoin() }
        }
    }

    @Test fun failedLoginShowsAnError() = withServer(LiveState(pending = listOf(interaction))) { owner, script, browser ->
        script.loginError = "Discovery failed"
        val vm = SessionViewModel(owner.sessions, Route.Chat(AgentId("main"), SessionId("s1")), browser = browser)
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                vm.state.first { it.pending.isNotEmpty() }
                vm.connect(interaction)
                val failed = vm.state.first { it.connecting[interaction.id] is ConnectState.Failed }.connecting[interaction.id] as ConnectState.Failed
                assertEquals("Discovery failed", failed.message)
                assertTrue(script.respondedConnected.isEmpty())
            } finally { clearAndJoin() }
        }
    }

    private class ScriptedBrowser : OAuthBrowser {
        override val redirect = McpRedirect.Loopback
        var callback: String? = null
        var opened: String? = null
        override suspend fun signIn(url: String): String? { opened = url; return callback }
    }

    private fun withServer(snapshot: LiveState, block: suspend (ConnectedServer, Script, ScriptedBrowser) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { mainDispatcher ->
            Dispatchers.setMain(mainDispatcher)
            try {
                withContext(mainDispatcher) {
                    withTimeout(15_000) {
                        val reader = object : DeviceReader {
                            override val kinds = emptyList<String>()
                            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                        }
                        val script = Script(snapshot)
                        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
                        val client = KinuvoClient("local:test", "test", connection = { script })
                        owner.open(client, "local:test")
                        client.connect()
                        owner.activate()
                        owner.harnesses.state.first { it.catalog != null }
                        try { block(owner, script, ScriptedBrowser()) } finally {
                            val job = owner.scope.coroutineContext.job
                            owner.close()
                            job.join()
                        }
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private inner class Script(private val snapshot: LiveState) : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val logins = mutableListOf<McpLoginParams>()
        val completions = mutableListOf<String>()
        val respondedConnected = mutableListOf<Boolean>()
        var loginError: String? = null
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "test", capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(main))
                Api.Models.name -> reply(request, Api.Models, ModelsCatalog())
                Api.ExecutionCatalog.name -> reply(request, Api.ExecutionCatalog, kinuvo.protocol.execution.ExecutionCatalog(emptyList(), emptyList()))
                Api.Session.name -> reply(request, Api.Session, SessionInfo(SessionId("s1"), AgentId("main"), "Connect a service"))
                Api.Capabilities.name -> reply(request, Api.Capabilities, SessionCapabilities(0, ServerCapabilities("test", features = setOf(ServerFeature.Attachments)), attachments = mapOf("text/plain" to CapabilityState())))
                Api.CatchUp.name -> {
                    reply(request, Api.CatchUp, Unit)
                    event(Api.Update, SessionUpdate(AgentId("main"), SessionId("s1"), SessionChange.Window(0, emptyList(), snapshot)))
                }
                Api.LoginMcp.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.LoginMcp.params, request.params)
                    logins += params
                    val error = loginError
                    if (error != null) frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, error)))
                    else reply(request, Api.LoginMcp, McpLogin("https://mcp.example.com/authorize?state=s1"))
                }
                Api.CompleteMcpLogin.name -> {
                    completions += ProtocolJson.decodeFromJsonElement(Api.CompleteMcpLogin.params, request.params).callbackUrl
                    reply(request, Api.CompleteMcpLogin, McpServerInfo("linear", url = "https://mcp.example.com/sse"))
                }
                Api.Respond.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.Respond.params, request.params)
                    (params.answer as? InteractionAnswer.Connect)?.let { respondedConnected += it.connected }
                    reply(request, Api.Respond, true)
                }
                else -> frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unsupported in test: ${request.method}")))
            }
        }
        suspend fun <T> event(type: EventType<T>, payload: T) = frames.send(Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), 1))
        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }
        override suspend fun close() = Unit
    }
}
