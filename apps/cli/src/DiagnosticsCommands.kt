package kinuvo.cli

import java.time.Instant
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kinuvo.server.jvm.diagnostics.DiagnosticFiles
import kinuvo.server.jvm.diagnostics.DiagnosticLogging
import kinuvo.server.jvm.diagnostics.diagnosticHome
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class DiagnosticsCommands : Group("diagnostics", "Collect local diagnostics without starting a Server.", ExportDiagnostics(), RecordDiagnostics(), DiagnosticsStatus())

internal fun diagnosticTimestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

private class ExportDiagnostics : Group("export", "Save a redacted ZIP of recent logs, crashes and traces.") {
    private val output by option("--output", "-o", help = "New ZIP file to create")
    override suspend fun run() {
        try {
            val file = File(output ?: "kinuvo-diagnostics-${diagnosticTimestamp()}.zip").absoluteFile
            DiagnosticFiles().writeZip(file, mapOf("this-device" to DiagnosticFiles().report("this-device (including local Server)")))
            echo("Saved $file\nReview the ZIP before sharing. Redaction is best effort; logs can contain private information.")
        } catch (e: Exception) { throw CliktError(e.message ?: "Couldn't export diagnostics") }
    }
}

private class RecordDiagnostics : Group("record", "Record debug logs for 1–30 minutes; use 0 to stop. Applies to running JVM processes using this home.") {
    private val minutes by argument("MINUTES").int().optional()
    override suspend fun run() {
        try {
            val status = DiagnosticLogging.record(minutes ?: 15)
            echo(if (status.recordingUntil > 0) "Debug recording enabled until ${Instant.ofEpochMilli(status.recordingUntil)}. Reproduce the problem, then run `kinuvo diagnostics export`." else "Debug recording stopped.")
        } catch (e: IllegalArgumentException) { throw CliktError(e.message ?: "Invalid duration") }
    }
}

private class DiagnosticsStatus : Group("status", "Show this home's diagnostic recording status.") {
    override suspend fun run() {
        val status = DiagnosticLogging.status()
        echo("Home: ${diagnosticHome()}\nDebug recording: ${if (status.recordingUntil > 0) "until ${Instant.ofEpochMilli(status.recordingUntil)}" else "off"}")
    }
}
