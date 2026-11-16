package kinuvo.ui.e2e

import kinuvo.app.diagnostics.LocalDiagnostics
import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.protocol.diagnostics.DiagnosticStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsFlowTest {
    @Test fun recordsStopsAndExportsWithoutUploading() {
        val local = FakeDiagnostics()
        appTest("diagnostics", localDiagnostics = local) {
            startChat()
            tap("Agents and sessions")
            tap("Home")
            tap("Settings")
            scrollTo("Help & diagnostics")
            tap("Help & diagnostics")
            await("Record debug logs")
            screenshot("help")
            tap("Record debug logs")
            await("Stop recording")
            tap("Stop recording")
            await("Record debug logs")
            tap("Export diagnostics…")
            await("Saved to .*diagnostics.zip.*")
            screenshot("exported")
            tap("Close")
        }
        assertEquals(listOf(15, 0), local.recordings)
        assertEquals(setOf("this-device"), local.saved.keys)
    }

    private class FakeDiagnostics : LocalDiagnostics {
        val recordings = mutableListOf<Int>()
        var saved = emptyMap<String, DiagnosticReport>()
        private var until = 0L
        override suspend fun status() = DiagnosticStatus(true, until)
        override suspend fun record(minutes: Int): DiagnosticStatus {
            recordings += minutes
            until = if (minutes > 0) Long.MAX_VALUE else 0
            return status()
        }
        override suspend fun report() = DiagnosticReport(mapOf("system.txt" to "Test Device"))
        override suspend fun save(destination: String, reports: Map<String, DiagnosticReport>) {
            assertEquals("/test/diagnostics.zip", destination)
            saved = reports
        }
    }
}
