package kinuvo.app.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.catching
import kinuvo.protocol.Api
import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.protocol.diagnostics.DiagnosticStatus
import kinuvo.protocol.diagnostics.RecordDiagnosticsParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface LocalDiagnostics {
    suspend fun status(): DiagnosticStatus
    suspend fun record(minutes: Int): DiagnosticStatus
    suspend fun report(): DiagnosticReport
    suspend fun save(destination: String, reports: Map<String, DiagnosticReport>)
}

fun interface DiagnosticFilePicker {
    /** Null means cancelled. */
    suspend fun pick(): String?
}

data class DiagnosticsState(
    val busy: Boolean = false,
    val local: DiagnosticStatus = DiagnosticStatus(),
    val server: DiagnosticStatus = DiagnosticStatus(),
    val saved: String? = null,
    val error: String? = null,
)

class DiagnosticsViewModel(
    private val local: LocalDiagnostics?,
    private val connected: ConnectedServer,
    private val picker: DiagnosticFilePicker?,
) : ViewModel() {
    val state: StateFlow<DiagnosticsState>
        field = MutableStateFlow(DiagnosticsState())

    fun refresh(server: Boolean) = work {
        val status = local?.status() ?: DiagnosticStatus()
        state.update { it.copy(local = status, server = DiagnosticStatus()) }
        val remote = if (server) connected.call { it.call(Api.DiagnosticsStatus, Unit) } else DiagnosticStatus()
        state.update { it.copy(local = status, server = remote) }
    }

    fun record(server: Boolean, minutes: Int) = work {
        if (server) {
            val status = connected.call { it.call(Api.DiagnosticsRecord, RecordDiagnosticsParams(minutes)) }
            state.update { it.copy(server = status) }
        } else {
            val status = local?.record(minutes) ?: error("Recording isn't available on this Device")
            state.update { it.copy(local = status) }
        }
    }

    fun export(includeServer: Boolean) = work {
        val collector = local ?: error("Saving diagnostics isn't available on this Device")
        val picker = picker ?: error("Saving diagnostics isn't available on this Device")
        suspend fun collect(): Map<String, DiagnosticReport> {
            val reports = linkedMapOf("this-device" to collector.report())
            if (includeServer) reports["selected-server"] = connected.call { it.call(Api.DiagnosticsExport, Unit) }
            return reports
        }
        val reports = if (includeServer) connected.run { collect() } else collect()
        val destination = picker.pick() ?: return@work
        collector.save(destination, reports)
        state.update { it.copy(saved = destination) }
    }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, saved = null) }
        viewModelScope.launch {
            try {
                catching { block() }.onFailure { error -> state.update { it.copy(error = error.message ?: "Couldn't collect diagnostics") } }
            } finally { state.update { it.copy(busy = false) } }
        }
    }
}
