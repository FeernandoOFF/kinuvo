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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactsViewModelTest {
    @Test fun editsKeepTheDraftWhenTheArtifactChangesUnderneath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        var info = ArtifactInfo("a1", "Page", "html", "main", "s1", 1, 1)
        var content = "<p>a</p>"
        var failSave = false
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Artifacts.name -> ProtocolJson.encodeToJsonElement(listOf(info))
                    Api.Artifact.name -> ProtocolJson.encodeToJsonElement(ArtifactData(info, content))
                    Api.SaveArtifact.name -> {
                        if (failSave) { frames.send(Frame.Response(request.id, false, error = "Too big")); return }
                        val save = ProtocolJson.decodeFromJsonElement<ArtifactSave>(request.params)
                        content = save.content
                        info = info.copy(updated = info.updated + 1)
                        ProtocolJson.encodeToJsonElement(info)
                    }
                    else -> { frames.send(Frame.Response(request.id, false, error = "Unused in test")); return }
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
            val vm = ArtifactsViewModel(client, Route.Artifacts("a1"), server)
            store.put("artifacts", vm)
            runCurrent()
            assertEquals(content, vm.state.value.open?.content)

            vm.edit()
            vm.change("<p>mine</p>")
            assertTrue(vm.state.value.dirty)
            assertFalse(vm.state.value.stale)

            info = info.copy(updated = 2); content = "<p>agent</p>"
            frames.send(Frame.Event(Api.ArtifactChanged.name, ProtocolJson.encodeToJsonElement(ArtifactChange("a1", info)), 1))
            runCurrent()
            assertEquals("<p>mine</p>", vm.state.value.draft?.text)
            assertTrue(vm.state.value.stale)
            vm.keepMine()
            assertFalse(vm.state.value.stale)

            failSave = true
            vm.save(); runCurrent()
            assertEquals("<p>mine</p>", vm.state.value.draft?.text)
            assertTrue(vm.state.value.errors.isNotEmpty())
            assertFalse(vm.state.value.saving)

            failSave = false
            vm.save(); runCurrent()
            assertNull(vm.state.value.draft)
            assertEquals("<p>mine</p>", content)
            assertEquals("<p>mine</p>", vm.state.value.open?.content)

            vm.edit(); vm.cancel()
            assertNull(vm.state.value.draft)
        } finally { store.clear(); server.close(); client.close(); Dispatchers.resetMain() }
    }
}
