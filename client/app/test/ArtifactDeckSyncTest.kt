package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.artifacts.*
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.artifact.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactDeckSyncTest {
    private fun report(h: Int, ready: Boolean = false, changed: Boolean = false) =
        """{"kinuvo":"deck","h":$h,"v":0,"n":${h + 1},"total":9,"notes":"n$h","ready":$ready,"changed":$changed}"""

    @Test fun aDeckPageSharesItsPositionThroughTheServer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val info = ArtifactInfo("deck", "Talk", "html", AgentId("main"), SessionId("s1"), 1, 1)
        var latest: JsonElement? = DeckPosition(3).toState()
        val watched = mutableListOf<String>()
        val sets = mutableListOf<ArtifactStateSet>()
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Artifacts.name -> ProtocolJson.encodeToJsonElement(listOf(info))
                    Api.Artifact.name -> ProtocolJson.encodeToJsonElement(ArtifactData(info, "<p/>"))
                    Api.WatchArtifactState.name -> { watched += ProtocolJson.decodeFromJsonElement<ArtifactRef>(request.params).id; ProtocolJson.encodeToJsonElement(ArtifactState("deck", latest)) }
                    Api.SetArtifactState.name -> { sets += ProtocolJson.decodeFromJsonElement<ArtifactStateSet>(request.params); buildJsonObject {} }
                    else -> { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unused in test"))); return }
                }
                frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { transport }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val store = ViewModelStore()
        try {
            client.connect(); server.open(client, "local:test"); server.config.attach(client)
            val vm = ArtifactsViewModel(Route.Artifacts("deck"), server)
            store.put("artifacts", vm)
            val commands = mutableListOf<String>()
            backgroundScope.launch { vm.pageCommands.collect { commands += it } }
            runCurrent()

            vm.pageMessage("""{"kinuvo":"design-ready"}""")
            vm.pageMessage("garbage")
            assertNull(vm.state.value.deck)
            assertEquals(emptyList(), watched)

            vm.pageMessage(report(0, ready = true))
            runCurrent()
            assertEquals("n0", vm.state.value.deck?.notes)
            assertEquals(listOf("deck"), watched)
            assertEquals(listOf(deckApply(DeckPosition(3))), commands)
            assertEquals(emptyList(), sets)

            vm.pageMessage(report(3))
            runCurrent()
            assertEquals(emptyList(), sets)

            vm.pageMessage(report(4, changed = true))
            runCurrent()
            assertEquals(listOf(DeckPosition(4).toState()), sets.map { it.state })
            vm.pageMessage(report(5, changed = true))
            vm.pageMessage(report(6, changed = true))
            runCurrent()
            assertEquals(1, sets.size)
            advanceTimeBy(300); runCurrent()
            assertEquals(listOf(DeckPosition(4), DeckPosition(6)), sets.map { deckPosition(it.state) })
            assertEquals("deck", sets.last().id)

            commands.clear()
            frames.send(Frame.Event(Api.ArtifactStateChanged.name, ProtocolJson.encodeToJsonElement(ArtifactState("deck", DeckPosition(8).toState())), 1))
            frames.send(Frame.Event(Api.ArtifactStateChanged.name, ProtocolJson.encodeToJsonElement(ArtifactState("other", DeckPosition(1).toState())), 2))
            runCurrent()
            assertEquals(listOf(deckApply(DeckPosition(8))), commands)

            commands.clear()
            vm.pageMessage(report(0, ready = true))
            runCurrent()
            assertEquals(listOf(deckApply(DeckPosition(8))), commands)
            assertEquals(1, watched.size)

            vm.step(true)
            runCurrent()
            assertEquals(deckStep(true), commands.last())
        } finally {
            store.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
