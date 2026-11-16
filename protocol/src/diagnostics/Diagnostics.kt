package kinuvo.protocol.diagnostics

import kotlinx.serialization.Serializable

/** Bounded text files, already redacted by their source. Never a Server backup. */
@Serializable
data class DiagnosticReport(val files: Map<String, String> = emptyMap())

@Serializable
data class DiagnosticStatus(val available: Boolean = false, val recordingUntil: Long = 0)

@Serializable
data class RecordDiagnosticsParams(val minutes: Int = 15)
