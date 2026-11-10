package kinuvo.app

import kinuvo.app.settings.EndpointViewModel
import kinuvo.app.onboarding.OnboardingState
import kinuvo.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EndpointTest {
    @Test fun discoveryOffersModelsAndChangingCredentialsInvalidatesThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var saved: CustomEndpointParams? = null
            val vm = EndpointViewModel({ listOf(ModelInfo("first"), ModelInfo("second")) }, { saved = it; ProviderRef("custom/endpoint") })
            vm.edit(CustomEndpointParams(baseUrl = "http://localhost:1234/v1"))
            assertFalse(vm.state.value.manual)
            vm.discover()
            runCurrent()
            assertEquals("first", vm.state.value.params.model)
            vm.edit(vm.state.value.params.copy(model = "second"))
            var selection = ""
            vm.save { _, model -> selection = model }
            runCurrent()
            assertEquals("second", selection)
            assertEquals("", saved?.model)
            vm.edit(CustomEndpointParams(baseUrl = "http://other/v1"))
            assertFalse(vm.state.value.discovered)
            assertFalse(vm.state.value.saveable)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedOrEmptyDiscoveryAllowsManualFallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (fails in listOf(false, true)) {
                val vm = EndpointViewModel({ if (fails) error("offline") else emptyList() }, { ProviderRef("custom/endpoint") })
                vm.edit(CustomEndpointParams(baseUrl = "http://localhost/v1"))
                vm.discover()
                runCurrent()
                assertTrue(vm.state.value.manual)
                assertNotNull(vm.state.value.reason)
                assertFalse(vm.state.value.saveable)
                vm.edit(vm.state.value.params.copy(model = "my-model"))
                assertTrue(vm.state.value.saveable)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun providersAreGroupedWithoutAnEmptyOnDeviceChoice() {
        val state = OnboardingState(providers = listOf(ProviderInfo("codex", "ChatGPT", account = ProviderAccount(false)),
            ProviderInfo("openai", "OpenAI"), ProviderInfo("apple", "Apple")))
        assertEquals(listOf("codex"), state.providerGroups[0].second)
        assertEquals(listOf("apple"), state.providerGroups[1].second)
        assertEquals(listOf("openai"), state.providerGroups[2].second)
        assertTrue(OnboardingState().providerGroups[1].second.isEmpty())
    }
}
