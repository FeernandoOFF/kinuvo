package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.onboarding.OnboardingViewModel
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.mcp.McpRedirect
import kinuvo.protocol.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSignInTest {
    @Test fun pastedAddressesGetTheirScheme() {
        assertEquals("http://127.0.0.1:1455/auth/callback?code=c&state=s", pastedCallback(" 127.0.0.1:1455/auth/callback?code=c&state=s\n"))
        assertEquals("http://127.0.0.1:1455/auth/callback?code=c", pastedCallback("http://127.0.0.1:1455/auth/callback?code=c"))
    }

    @Test fun browserCompletionRefreshesModels() = runTest {
        fixture("http://127.0.0.1:3456/auth/callback?state=test&code=ok") { vm, completed ->
            vm.signInWithChatGPT(); runCurrent()
            assertNull(vm.state.value.providerSignIn)
            assertEquals(1, completed.size)
            assertEquals("first", vm.state.value.model)
        }
    }
    @Test fun pasteCompletionAndCancellation() = runTest {
        val requests = mutableListOf<String>()
        fixture(null, requests = requests) { vm, completed ->
            vm.signInWithChatGPT(); runCurrent()
            assertEquals(true, vm.state.value.providerSignIn?.awaitingPaste)
            assertEquals("https://auth.test/?state=test", vm.state.value.providerSignIn?.url)
            vm.completeChatGPTSignIn("http://127.0.0.1:3456/auth/callback?state=test&code=ok"); runCurrent()
            assertNull(vm.state.value.providerSignIn)
            assertEquals(1, completed.size)
            vm.signInWithChatGPT(anotherAccount = true, requestConsent = true); runCurrent(); vm.cancelChatGPTSignIn(); runCurrent()
            assertNull(vm.state.value.providerSignIn)
            assertEquals(Api.CancelProviderSignIn.name, requests.last())
        }
    }
    @Test fun completionErrorRemainsReviewable() = runTest {
        fixture(null, fail = true) { vm, _ ->
            vm.signInWithChatGPT(); runCurrent()
            vm.completeChatGPTSignIn("bad"); runCurrent()
            assertEquals(true, vm.state.value.providerSignIn?.awaitingPaste)
            assertNotNull(vm.state.value.providerSignIn?.error)
        }
    }
    private suspend fun TestScope.fixture(callback: String?, fail: Boolean = false, requests: MutableList<String> = mutableListOf(), block: suspend TestScope.(OnboardingViewModel, List<String>) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val completed = mutableListOf<String>()
        var starts = 0
        var cancels = 0
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                requests += request.method
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.CancelProviderSignIn.name -> { cancels++; buildJsonObject {} }
                    Api.SignInProvider.name -> {
                        val params = ProtocolJson.decodeFromJsonElement<ProviderSignInParams>(request.params)
                        assertEquals("codex", params.provider)
                        if (++starts == 2) { assertTrue(params.anotherAccount); assertTrue(params.requestConsent) }
                        assertEquals("http://127.0.0.1:3456/auth/callback", params.redirectUri)
                        JsonPrimitive("https://auth.test/?state=test")
                    }
                    Api.CompleteProviderSignIn.name -> {
                        if (fail) { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Sign-in rejected"))); return }
                        completed += ProtocolJson.decodeFromJsonElement<ProviderSignInCompleteParams>(request.params).callbackUrl
                        buildJsonObject {}
                    }
                    Api.Providers.name -> ProtocolJson.encodeToJsonElement(listOf(ProviderInfo("codex", "Codex", ProviderAccount(true, usesChatGptPlan = true))))
                    Api.Models.name -> ProtocolJson.encodeToJsonElement(ModelsCatalog(listOf(ProviderModels("codex", "Codex", listOf(ModelInfo("first"))))))
                    else -> { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unused"))); return }
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
            val browser = object : OAuthBrowser {
                override val redirect = McpRedirect.Loopback
                override suspend fun signIn(url: String): String? = error("MCP browser must not be used")
                override suspend fun signInWithLoopback(authorize: suspend (String) -> String, opened: (String, Boolean) -> Unit): String? {
                    opened(authorize("http://127.0.0.1:3456/auth/callback"), callback == null)
                    return callback
                }
            }
            val vm = OnboardingViewModel(server.config, browser = browser)
            store.put("vm", vm)
            vm.edit { it.copy(provider = "codex") }
            runCurrent()
            block(vm, completed)
            assertTrue(cancels >= starts)
        } finally { store.clearAndJoin(); server.close(); client.close(); runCurrent(); Dispatchers.resetMain() }
    }
}
