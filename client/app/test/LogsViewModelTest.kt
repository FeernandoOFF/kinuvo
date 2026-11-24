package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.diagnostics.LogSharing
import kinuvo.app.diagnostics.LogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private class Fake(var crashed: Boolean = false, val fail: Boolean = false) : LogSharing {
        var bundles = 0
        override fun crashedLastTime() = crashed
        override fun dismissCrash() { crashed = false }
        override suspend fun bundle(): String { bundles++; if (fail) error("Disk full"); return "/cache/kinuvo-logs.zip" }
    }

    private fun withViewModel(sharing: LogSharing?, block: suspend (LogsViewModel, () -> Unit) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = LogsViewModel(sharing)
        val store = ViewModelStore().also { it.put("logs", vm) }
        try { block(vm) { runCurrent() } } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun offersToShareAfterACrashUntilDismissed() = withViewModel(Fake(crashed = true)) { vm, _ ->
        assertTrue(vm.state.value.crashed)
        vm.dismissCrash()
        assertFalse(vm.state.value.crashed)
    }

    @Test fun sharingCreatesTheBundleOnceAndSettlesTheCrashPrompt() {
        val sharing = Fake(crashed = true)
        withViewModel(sharing) { vm, run ->
            vm.share()
            vm.share()
            run()
            assertEquals(1, sharing.bundles)
            assertEquals("/cache/kinuvo-logs.zip", vm.state.value.bundle)
            assertFalse(vm.state.value.crashed)
            assertFalse(sharing.crashed)
            assertFalse(vm.state.value.busy)
            assertEquals("/cache/kinuvo-logs.zip", vm.takeBundle())
            assertNull(vm.takeBundle())
            assertNull(vm.state.value.bundle)
        }
    }

    @Test fun aFailureIsReportedWithoutABundle() = withViewModel(Fake(crashed = true, fail = true)) { vm, run ->
        vm.share()
        run()
        assertEquals("Disk full", vm.state.value.error)
        assertNull(vm.state.value.bundle)
        assertTrue(vm.state.value.crashed)
        assertFalse(vm.state.value.busy)
    }

    @Test fun isUnavailableWithoutAPlatformImplementation() = withViewModel(null) { vm, run ->
        assertFalse(vm.available)
        vm.share()
        run()
        assertFalse(vm.state.value.busy)
    }
}
