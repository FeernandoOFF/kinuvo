package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentDefaultsTest {
    private val serverJobs = mutableListOf<Job>()
    private val stores = mutableListOf<ViewModelStore>()
    private fun store() = ViewModelStore().also(stores::add)
    @Test fun savedDefaultsUpdateOpenAndFutureNewSessionsWithoutBecomingOverrides() = withOwner { owner, _, _ ->
        val script = Script()
        connect(owner, script)
        val store = store()
        fun newChat(key: String) = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("agent", null)).also { store.put(key, it) }
        try {
            val opened = newChat("opened")
            opened.state.first { it.defaults != null && it.capabilities?.revision == 0L }
            opened.configure(SessionSettings(approvals = "strict"))
            owner.config.updateAgent(UpdateAgentParams("agent", model = "new-model", reasoning = "high"))
            opened.state.first { it.defaults?.reasoning == "high" && it.capabilities?.revision == 1L }
            assertEquals("new-model", opened.state.value.defaults?.model)
            assertEquals(SessionSettings(approvals = "strict"), opened.state.value.settings)
            val future = newChat("future")
            future.state.first { it.defaults?.reasoning == "high" && !it.resolving }
            future.send("hello")
            assertEquals(SessionSettings(), script.sent.receive())
            future.state.first { !it.sending }
            val explicit = newChat("explicit")
            explicit.state.first { it.defaults?.reasoning == "high" && !it.resolving }
            explicit.configure(SessionSettings(reasoning = "low"))
            owner.config.updateAgent(UpdateAgentParams("agent", reasoning = "medium"))
            explicit.state.first { it.defaults?.reasoning == "medium" }
            assertEquals("low", explicit.state.value.settings.reasoning)
            explicit.send("hello")
            assertEquals("low", script.sent.receive().reasoning)
        } finally { store.clearAndJoin() }
    }

    @Test fun reflectionReviewsSendTrimmedNotesForBothDecisions() = withOwner { owner, _, _ ->
        val script = Script()
        connect(owner, script)
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("agent", "session"))
        store().put("review", vm)
        for (approve in listOf(true, false)) {
            vm.reviewReflection("proposal", approve, "  wrong date  ")
            assertEquals(ReviewReflectionParams("agent", "session", "proposal", approve, "wrong date"), script.reviews.receive())
        }
        vm.reviewReflection("proposal", false, "  ")
        assertNull(script.reviews.receive().note)
    }

    @Test fun oldDetailsReadCannotOverwriteSavedDefaults() = withOwner { owner, _, _ ->
        val script = Script()
        connect(owner, script)
        script.holdDetails = true
        val old = owner.config.loadDetails("agent")
        script.blocked.receive()
        owner.config.updateAgent(UpdateAgentParams("agent", reasoning = "high"))
        script.release.complete(Unit)
        old.join()
        assertEquals("high", owner.config.state.value["agent"]?.details?.settings?.reasoning)
    }

    private fun withOwner(block: suspend (ConnectedServer, MutableList<Notice>, DeviceAccess) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                withContext(main) {
                    withTimeout(15_000) {
                        val prefs = MemoryPreferences()
                        val reader = object : DeviceReader {
                            override val kinds = emptyList<String>()
                            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                        }
                        val notices = mutableListOf<Notice>()
                        val access = DeviceAccess(prefs, reader)
                        val owner = ConnectedServer(Notifications(listOf(Notifier { notices += it })), access)
                        try { block(owner, notices, access) } finally {
                            stores.forEach { it.clearAndJoin() }
                            serverJobs += owner.scope.coroutineContext.job
                            owner.close()
                            serverJobs.forEach { it.join() }
                        }
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private suspend fun connect(owner: ConnectedServer, script: Script) {
        val client = KinuvoClient("local:test", "test", connection = { script })
        owner.open(client, "local:test")
        serverJobs += owner.scope.coroutineContext.job
        client.connect()
        owner.activate()
    }

    private class Script : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        var settings = AgentSettings("model", "medium", "default", "none")
        var sessionSettings = SessionSettings()
        var revision = 0L
        var holdDetails = false
        val blocked = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val sent = Channel<SessionSettings>(Channel.UNLIMITED)
        val reviews = Channel<ReviewReflectionParams>(Channel.UNLIMITED)
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "defaults", capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(AgentInfo("agent", "Agent", "none")))
                Api.Agent.name -> {
                    val details = AgentDetails(AgentInfo("agent", "Agent", "none"), settings, "", "")
                    if (holdDetails) { blocked.send(Unit); release.await() }
                    reply(request, Api.Agent, details)
                }
                Api.UpdateAgent.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.UpdateAgent.params, request.params)
                    revision++
                    settings = settings.copy(model = params.model ?: settings.model, reasoning = params.reasoning ?: settings.reasoning)
                    reply(request, Api.UpdateAgent, AgentDetails(AgentInfo("agent", "Agent", "none"), settings, "", ""))
                }
                Api.Capabilities.name -> reply(request, Api.Capabilities, SessionCapabilities(revision, ServerCapabilities("test")))
                Api.Models.name -> reply(request, Api.Models, ModelsCatalog())
                Api.Providers.name -> reply(request, Api.Providers, emptyList())
                Api.Skills.name -> reply(request, Api.Skills, emptyList())
                Api.McpServers.name -> reply(request, Api.McpServers, emptyList())
                Api.CreateSession.name -> { sessionSettings = SessionSettings(); reply(request, Api.CreateSession, SessionInfo("new", "agent", "New")) }
                Api.ConfigureSession.name -> {
                    sessionSettings = ProtocolJson.decodeFromJsonElement(Api.ConfigureSession.params, request.params).settings
                    reply(request, Api.ConfigureSession, SessionInfo("new", "agent", "New", settings = sessionSettings))
                }
                Api.Send.name -> { sent.send(sessionSettings); reply(request, Api.Send, SendResult("new", "run", "hello")) }
                Api.Session.name -> reply(request, Api.Session, SessionInfo("session", "agent", "Review"))
                Api.ReviewReflection.name -> {
                    reviews.send(ProtocolJson.decodeFromJsonElement(Api.ReviewReflection.params, request.params))
                    reply(request, Api.ReviewReflection, Unit)
                }
                Api.CatchUp.name -> reply(request, Api.CatchUp, Unit)
                else -> frames.send(Frame.Response(request.id, false, error = "Unsupported in test: ${request.method}"))
            }
        }
        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }
        override suspend fun close() = Unit
    }
}
