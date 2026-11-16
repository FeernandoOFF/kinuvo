package kinuvo.server

import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.protocol.diagnostics.DiagnosticStatus

/** Host-owned collection; callers must have Server management access. */
interface Diagnostics {
    suspend fun status(): DiagnosticStatus
    suspend fun export(): DiagnosticReport
    suspend fun record(minutes: Int): DiagnosticStatus

    object Unavailable : Diagnostics {
        override suspend fun status() = DiagnosticStatus()
        override suspend fun export(): DiagnosticReport = error("Diagnostics export isn't available on this Server")
        override suspend fun record(minutes: Int): DiagnosticStatus = error("Diagnostic recording isn't available on this Server")
    }
}
