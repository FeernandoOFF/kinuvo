package kinuvo.desktop

import kinuvo.app.diagnostics.LocalDiagnostics
import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.server.jvm.JvmDiagnostics
import kinuvo.server.jvm.kinuvoHome
import java.io.File

internal class DesktopDiagnostics(
    private val diagnostics: JvmDiagnostics = JvmDiagnostics(File(kinuvoHome().toString()), "this-device (including local Server)"),
) : LocalDiagnostics {
    override suspend fun status() = diagnostics.status()
    override suspend fun record(minutes: Int) = diagnostics.record(minutes)
    override suspend fun report() = diagnostics.export()
    override suspend fun save(destination: String, reports: Map<String, DiagnosticReport>) = diagnostics.save(File(destination), reports)
}
