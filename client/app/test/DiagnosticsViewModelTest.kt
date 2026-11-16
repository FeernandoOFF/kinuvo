package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.Notifications
import kinuvo.app.connection.Preferences
import kinuvo.app.device.DeviceAccess
import kinuvo.app.device.DeviceReader
import kinuvo.app.diagnostics.DiagnosticFilePicker
import kinuvo.app.diagnostics.DiagnosticsViewModel
import kinuvo.app.diagnostics.LocalDiagnostics
import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.protocol.diagnostics.DiagnosticStatus
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

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @Test fun cancellingThePickerDoesNotWriteAnArchive() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val local = FakeDiagnostics()
        val server = connected()
        val vm = DiagnosticsViewModel(local, server, DiagnosticFilePicker { null })
        val store = ViewModelStore().also { it.put("diagnostics", vm) }
        try {
            vm.export(false)
            runCurrent()
            assertEquals(0, local.writes)
            assertNull(vm.state.value.saved)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.busy)
        } finally { store.clear(); server.close(); Dispatchers.resetMain() }
    }

    @Test fun archiveFailureIsReportedWithoutClaimingASavedFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val local = FakeDiagnostics(fail = true)
        val server = connected()
        val vm = DiagnosticsViewModel(local, server, DiagnosticFilePicker { "/test/report.zip" })
        val store = ViewModelStore().also { it.put("diagnostics", vm) }
        try {
            vm.export(false)
            runCurrent()
            assertEquals(1, local.writes)
            assertEquals("Disk full", vm.state.value.error)
            assertNull(vm.state.value.saved)
            assertFalse(vm.state.value.busy)
        } finally { store.clear(); server.close(); Dispatchers.resetMain() }
    }

    private class FakeDiagnostics(private val fail: Boolean = false) : LocalDiagnostics {
        var writes = 0
        override suspend fun status() = DiagnosticStatus(true)
        override suspend fun record(minutes: Int) = status()
        override suspend fun report() = DiagnosticReport(mapOf("system.txt" to "Test"))
        override suspend fun save(destination: String, reports: Map<String, DiagnosticReport>) {
            writes++
            if (fail) error("Disk full")
        }
    }

    private fun connected(): ConnectedServer {
        val prefs = object : Preferences {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) {}
            override fun clear() {}
        }
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = completion("{}")
        }
        return ConnectedServer(Notifications(emptyList()), DeviceAccess(prefs, reader))
    }
}
