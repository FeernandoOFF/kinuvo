package kinuvo.app.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** This Device's logs and crash files, and the ZIP that shares them. Not bound where the platform keeps none, e.g. the browser. */
interface LogSharing {
    /** Whether the app crashed in its last session and the user hasn't been asked about it since. */
    fun crashedLastTime(): Boolean
    fun dismissCrash()
    /** Writes the ZIP and returns its path. */
    suspend fun bundle(): String
}

/** [bundle] is a ZIP waiting to be handed to the platform's share sheet or file manager. */
data class LogsState(val crashed: Boolean = false, val busy: Boolean = false, val bundle: String? = null, val error: String? = null)

class LogsViewModel(private val sharing: LogSharing?) : ViewModel() {
    val available get() = sharing != null

    val state: StateFlow<LogsState>
        field = MutableStateFlow(LogsState(crashed = sharing?.crashedLastTime() == true))

    fun share() {
        val sharing = sharing ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            catching { sharing.bundle() }
                .onSuccess { path -> sharing.dismissCrash(); state.update { it.copy(busy = false, crashed = false, bundle = path) } }
                .onFailure { e -> state.update { it.copy(busy = false, error = e.message ?: "Couldn't collect the logs") } }
        }
    }

    /** The ZIP to hand to the platform, once: whichever screen sees it first takes it. */
    fun takeBundle(): String? {
        var taken: String? = null
        state.update { taken = it.bundle; it.copy(bundle = null) }
        return taken
    }

    fun dismissCrash() {
        sharing?.dismissCrash()
        state.update { it.copy(crashed = false) }
    }
}
