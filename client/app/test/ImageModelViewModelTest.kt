package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*
import kinuvo.app.settings.SettingsViewModel
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.model.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImageModelViewModelTest {
    @Test fun loadsSavesAndClearsServerImageModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val ref = ProviderModelRef("openai", "gpt-image-1")
        var saved: ProviderModelRef? = ref
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Models.name -> ProtocolJson.encodeToJsonElement(ModelsCatalog(listOf(ProviderModels("openai", "OpenAI", listOf(ModelInfo(ref.model, output = listOf("image")))))))
                    Api.Providers.name -> JsonArray(emptyList())
                    Api.ImageModel.name -> ProtocolJson.encodeToJsonElement(saved)
                    Api.SaveImageModel.name -> { saved = ProtocolJson.decodeFromJsonElement(request.params); ProtocolJson.encodeToJsonElement(saved) }
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
            val vm = SettingsViewModel(server.routines, server.config, AppSchemeBrowser {}, Route.Settings(AgentId("agent"), SettingsSection.Images))
            store.put("settings", vm)
            runCurrent()
            assertEquals(ref, vm.state.value.imageModel)
            vm.saveImageModel(null).join()
            assertNull(saved)
            assertNull(vm.state.value.imageModel)
            vm.saveImageModel(ref).join()
            assertEquals(ref, saved)
            assertTrue(vm.state.value.saved)
            vm.saveImageModel(ProviderModelRef("openai", "text-only")).join()
            assertEquals(ref, saved)
            assertTrue(vm.state.value.errors.isNotEmpty())
        } finally { store.clear(); server.close(); client.close(); Dispatchers.resetMain() }
    }
}
