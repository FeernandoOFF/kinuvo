package kinuvo.app.native

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Main-thread callbacks until cancelled. Destinations cancel observations before clearing their ViewModels. */
class StateObservation internal constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal fun <S> collect(state: StateFlow<S>, onState: (S) -> Unit) {
        scope.launch { state.collect(onState) }
    }

    fun cancel() = scope.cancel()
}

fun <S> StateFlow<S>.observe(onState: (S) -> Unit): StateObservation =
    StateObservation().also { it.collect(this, onState) }

/** Owned by the native destination, released when that destination leaves its navigation hierarchy. */
class DestinationLifetime {
    val store = ViewModelStore()
    private val observations = mutableListOf<StateObservation>()
    private var closed = false
    private var nextKey = 0

    fun <V : ViewModel> own(viewModel: V): V {
        check(!closed)
        store.put("${nextKey++}", viewModel)
        return viewModel
    }

    fun <S> observe(state: StateFlow<S>, onState: (S) -> Unit): StateObservation {
        check(!closed)
        return state.observe(onState).also(observations::add)
    }

    fun close() {
        if (closed) return
        closed = true
        observations.forEach(StateObservation::cancel)
        observations.clear()
        store.clear()
    }
}
