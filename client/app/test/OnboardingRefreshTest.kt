package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.onboarding.OnboardingViewModel
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingRefreshTest {
    @Test fun selectionRefreshesModelsAndKeepsOnlyValidChoices() = runTest {
        withOnboarding {
            vm.selectProvider("codex")
            runCurrent()
            assertEquals("first", vm.state.value.model)
            assertEquals(1, refreshes)
            vm.selectProvider("codex")
            vm.edit { it.copy(model = "second") }
            runCurrent()
            assertEquals("second", vm.state.value.model)
            catalog = models("replacement")
            vm.selectProvider("codex")
            runCurrent()
            assertEquals("replacement", vm.state.value.model)
            assertFalse(vm.state.value.busy)
        }
    }

    @Test fun switchingProviderCancelsPendingSelection() = runTest {
        withOnboarding {
            holdModels = true
            vm.selectProvider("codex")
            runCurrent()
            vm.selectProvider("openai")
            runCurrent()
            for (request in pending.toList()) frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(catalog)))
            runCurrent()
            assertEquals("openai", vm.state.value.provider)
            assertEquals("api", vm.state.value.model)
            assertFalse(vm.state.value.busy)
        }
    }

    @Test fun pushedSignInClearsDeviceCodeAndLoadsModels() = runTest {
        withOnboarding {
            vm.edit { it.copy(provider = "codex", model = "obsolete", deviceCode = DeviceCode("https://example.com", "code", 100)) }
            accounts = listOf(ProviderInfo("codex", "Codex", account = ProviderAccount(true)))
            frames.send(Frame.Event(Api.ProvidersChanged.name, ProtocolJson.encodeToJsonElement(accounts), 1))
            runCurrent()
            assertNull(vm.state.value.deviceCode)
            assertEquals("first", vm.state.value.model)
            assertEquals(1, refreshes)
            frames.send(Frame.Event(Api.ProvidersChanged.name, ProtocolJson.encodeToJsonElement(accounts), 2))
            runCurrent()
            assertEquals(1, refreshes)
        }
    }

    private fun models(vararg ids: String) = ModelsCatalog(listOf(
        ProviderModels("codex", "Codex", ids.map { ModelInfo(it) }),
        ProviderModels("openai", "OpenAI", listOf(ModelInfo("api"))),
    ))

    private inner class Fixture {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        var catalog = models("first", "second")
        var accounts = listOf(ProviderInfo("codex", "Codex", account = ProviderAccount(false)))
        var refreshes = 0
        var holdModels = false
        val pending = mutableListOf<Frame.Request>()
        lateinit var vm: OnboardingViewModel
    }

    private suspend fun TestScope.withOnboarding(block: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        val transport = object : FrameConnection {
            override val incoming = f.frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Providers.name -> ProtocolJson.encodeToJsonElement(f.accounts)
                    Api.Models.name -> {
                        assertTrue(ProtocolJson.decodeFromJsonElement<ModelsParams>(request.params).refresh)
                        f.refreshes++
                        if (f.holdModels) { f.pending += request; return }
                        ProtocolJson.encodeToJsonElement(f.catalog)
                    }
                    else -> { f.frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unused in test"))); return }
                }
                f.frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { f.frames.close() }
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
            f.vm = OnboardingViewModel(server.config)
            store.put("onboarding", f.vm)
            runCurrent()
            f.block()
        } finally { store.clearAndJoin(); server.close(); client.close(); runCurrent(); Dispatchers.resetMain() }
    }
}
