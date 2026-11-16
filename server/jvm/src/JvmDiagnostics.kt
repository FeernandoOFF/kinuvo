package kinuvo.server.jvm

import kinuvo.server.jvm.diagnostics.DiagnosticFiles
import kinuvo.server.jvm.diagnostics.DiagnosticLogging
import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.server.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class JvmDiagnostics(private val home: File, private val scope: String = "server") : Diagnostics {
    override suspend fun status() = withContext(Dispatchers.IO) { DiagnosticLogging.status(home) }
    override suspend fun export() = withContext(Dispatchers.IO) { DiagnosticFiles(home).report(scope) }
    override suspend fun record(minutes: Int) = withContext(Dispatchers.IO) { DiagnosticLogging.record(minutes, home) }
    suspend fun save(destination: File, reports: Map<String, DiagnosticReport>) = withContext(Dispatchers.IO) {
        DiagnosticFiles(home).writeZip(destination, reports)
    }
}
