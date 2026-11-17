package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.onboarding.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingLoadingTest {
    @Test fun missingAgentEndsLoadingWithAnActionableError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        }
        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val onboarding = OnboardingViewModel(owner.config)
        store.put("onboarding", onboarding)
        try {
            onboarding.edit { it.copy(busy = true) }
            onboarding.load(null)
            assertFalse(onboarding.state.value.busy)
            assertNull(onboarding.state.value.details)
            assertTrue(onboarding.state.value.error.orEmpty().contains("try again"))
        } finally { store.clearAndJoin(); owner.close(); runCurrent(); Dispatchers.resetMain() }
    }
}
