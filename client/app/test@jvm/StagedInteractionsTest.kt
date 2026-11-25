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

/** After answering one of several interactions, [SessionViewModel] offers the next one that still needs an answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class StagedInteractionsTest {
    private val main = AgentInfo(AgentId("main"), "Main", "native")
    private fun approval(id: String) = PendingInteraction.Approval(InteractionId(id), ToolCallId("c-$id"), "deploy", "{}", null, Risk.High, null, false)
    private fun SessionState.shownRequest() = pendingRequests(items, pending, staged).first().id
    private val first = approval("a1")
    private val second = approval("a2")

    @Test fun theNextApprovalIsShownOnceTheFirstIsStaged() = withServer(LiveState(pending = listOf(first, second))) { owner, script ->
        val vm = SessionViewModel(owner.sessions, Route.Chat(AgentId("main"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                vm.state.first { it.pending.size == 2 }
                vm.respond(ApprovalDecision.AllowOnce)
                vm.state.first { first.id in it.staged }
                assertEquals(second.id, vm.state.value.shownRequest())
                vm.respond(ApprovalDecision.AllowOnce)
                withTimeout(5_000) { while (script.responded.size < 2) delay(10) }
                assertEquals(listOf(first.id, second.id), script.responded)
            } finally { clearAndJoin() }
        }
    }

    @Test fun aSnapshotWithStagedAnswersOffersTheOthers() = withServer(LiveState(pending = listOf(first, second), staged = setOf(first.id))) { owner, script ->
        val vm = SessionViewModel(owner.sessions, Route.Chat(AgentId("main"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                vm.state.first { it.pending.size == 2 }
                assertEquals(second.id, vm.state.value.shownRequest())
                vm.respond(ApprovalDecision.AllowOnce)
                withTimeout(5_000) { while (script.responded.isEmpty()) delay(10) }
                assertEquals(listOf(second.id), script.responded)
            } finally { clearAndJoin() }
        }
    }

    private fun withServer(snapshot: LiveState, block: suspend (ConnectedServer, Script) -> Unit) = runBlocking {
        val script = Script(snapshot)
        var stage = "starting"
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { mainDispatcher ->
            Dispatchers.setMain(mainDispatcher)
            try {
                withContext(mainDispatcher) {
                    try {
                        withTimeout(15_000) {
                            val reader = object : DeviceReader {
                                override val kinds = emptyList<String>()
                                override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                                override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                            }
                            val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
                            val client = KinuvoClient("local:test", "test", connection = { script })
                            owner.open(client, "local:test")
                            client.connect()
                            owner.activate()
                            stage = "waiting for the harness catalog"
                            owner.harnesses.state.first { it.catalog != null }
                            stage = "running the test"
                            try { block(owner, script) } finally {
                                stage = "closing the Server"
                                val job = owner.scope.coroutineContext.job
                                owner.close()
                                job.join()
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw AssertionError("Timed out $stage; requests seen: ${script.methods}", e)
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private inner class Script(private val snapshot: LiveState) : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val responded = mutableListOf<InteractionId>()
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            methods += request.method
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "test", capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(main))
                Api.Models.name -> reply(request, Api.Models, ModelsCatalog())
                Api.ExecutionCatalog.name -> reply(request, Api.ExecutionCatalog, kinuvo.protocol.execution.ExecutionCatalog(emptyList(), emptyList()))
                Api.Session.name -> reply(request, Api.Session, SessionInfo(SessionId("s1"), AgentId("main"), "Connect a service"))
                Api.Capabilities.name -> reply(request, Api.Capabilities, SessionCapabilities(0, ServerCapabilities("test", features = setOf(ServerFeature.Attachments)), attachments = mapOf("text/plain" to CapabilityState())))
                Api.Subscribe.name -> {
                    reply(request, Api.Subscribe, Unit)
                    event(Api.Update, SessionUpdate(AgentId("main"), SessionId("s1"), SessionChange.Snapshot(SessionSnapshot(0, 0, emptyList(), snapshot))))
                }
                Api.Respond.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.Respond.params, request.params)
                    responded += params.requestId
                    reply(request, Api.Respond, true)
                    event(Api.Update, SessionUpdate(AgentId("main"), SessionId("s1"), SessionChange.Item(AgentEvent.InteractionStaged(params.requestId))))
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
