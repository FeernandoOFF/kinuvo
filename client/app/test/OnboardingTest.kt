package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.app.device.DeviceAccess
import kinuvo.app.device.DeviceReader
import kinuvo.client.KinuvoClient
import kinuvo.app.onboarding.*

import kinuvo.protocol.agent.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.model.*
import kinuvo.protocol.service.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingTest {
    @Test fun androidModelsNeedThreeGigabytesAndArm64() {
        assertTrue(OnDeviceSupport.android(OnDeviceSupport.MinimumAndroidRam, false, true))
        assertFalse(OnDeviceSupport.android(OnDeviceSupport.MinimumAndroidRam - 1, false, true))
        assertFalse(OnDeviceSupport.android(8_000_000_000, true, true))
        assertFalse(OnDeviceSupport.android(8_000_000_000, false, false))
    }

    @Test fun capabilityFilteringOnlyAppliesToTheLocalServer() {
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val connection = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader),
            onDevice = OnDeviceSupport { it != "apple" && it != "local" })
        connection.open(KinuvoClient("local://kinuvo", "token"), "local://kinuvo")
        assertFalse(connection.offers("apple"))
        assertFalse(connection.offers("local"))
        assertTrue(connection.offers("codex"))
        connection.open(KinuvoClient("ws://server/ws", "token"), "ws://server/ws")
        assertTrue(connection.offers("apple"))
        assertTrue(connection.offers("local"))
        connection.close()
    }

    @Test fun unfinishedSetupSurvivesConnectionAndRestart() {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials)
        assertFalse(servers.onboardingComplete)
        servers.onboardingComplete = false
        servers.select(servers.remember(Server.Remote("ws://home:8787/ws"), "token"))
        assertFalse(Servers(prefs, credentials = prefs.credentials).onboardingComplete)
        servers.onboardingComplete = true
        assertTrue(Servers(prefs, credentials = prefs.credentials).onboardingComplete)
    }

    @Test fun modelSelectionNeedsAvailableModelAndProviderAccess() {
        val details = AgentDetails(AgentInfo("main", "Kinuvo", "none"), AgentSettings("model", "medium", "default", "none"), "", "")
        val state = OnboardingState(details = details, provider = "codex", model = "model", providers = listOf(ProviderInfo("codex", "Codex")))
        assertTrue(state.modelReady)
        assertFalse(state.copy(providers = emptyList()).modelReady)
        assertFalse(state.copy(providers = listOf(ProviderInfo("codex", "Codex", account = ProviderAccount(false)))).modelReady)
        assertFalse(state.copy(providers = listOf(ProviderInfo("codex", "Codex", apiKey = ProviderApiKeyStatus(false, "server")))).modelReady)
        assertFalse(state.copy(catalog = ModelsCatalog(listOf(ProviderModels("codex", "Codex", listOf(ModelInfo("model", state = CapabilityState.unavailable("Not installed"))))))).modelReady)
    }

    @Test fun personalitySelectionsChangeTheInstructions() {
        val companion = personalityPrompt(listOf(0, 0, 0, 0, 0))
        val coworker = personalityPrompt(listOf(1, 1, 1, 1, 1))
        assertTrue("companion" in companion && "initiative" in companion && "humor" in companion)
        assertTrue("coworker" in coworker && "Check in" in coworker && "blunt" in coworker)
        assertTrue("No emoji" in coworker && "No emoji" in companion)
    }

    @Test fun notificationsAreSetUpWhenPushedOrSentThroughAProvider() {
        assertTrue(ServiceSettings().notifies(pushes = true))
        assertFalse(ServiceSettings().notifies(pushes = false))
        assertFalse(ServiceSettings(push = false).notifies(pushes = true))
        assertTrue(ServiceSettings("ntfy", push = false).notifies(pushes = false))
    }

    @Test fun aNewAgentOnlyStepsThroughIdentityAndSoul() {
        val state = OnboardingState(step = 4, steps = AgentSteps)
        assertEquals(5, state.moved(1).step)
        assertEquals(5, state.moved(1).moved(1).step)
        assertEquals(4, state.moved(-1).step)
        assertEquals(2, OnboardingState().moved(1).step)
    }
}
