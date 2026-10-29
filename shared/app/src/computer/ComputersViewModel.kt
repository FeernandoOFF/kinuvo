package kinuvo.app.computer

import kinuvo.app.connection.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** [computers] is null until loaded; [frame] is the latest Live view frame of [selected]. */
data class ComputersState(
    val computers: List<ComputerInfo>? = null,
    val selected: String? = null,
    val frame: ComputerFrame? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val computer get() = computers?.firstOrNull { it.id == selected }
    val control get() = computer?.control ?: ComputerControl.Idle
}

/** The Computers, and Live view of the one [Route.Computers.computer] opens: frames while its screen is open, and the user's Takeover and input. */
class ComputersViewModel(private val client: KinuvoClient, route: Route.Computers, private val owner: ConnectedServer) : ViewModel() {
    val state: StateFlow<ComputersState>
        field = MutableStateFlow(ComputersState(selected = route.computer))
    private var watching: String? = null
    private val watchLock = kotlinx.coroutines.sync.Mutex()

    init {
        owner.bind(viewModelScope, client)
        viewModelScope.launch { client.state.collect { if (it is ConnectionState.Connected) { watching = null; load() } } }
        viewModelScope.launch { client.events(Api.ComputersChanged).collect { all -> update(all); attempt { watch() } } }
        viewModelScope.launch {
            client.events(Api.ComputerFrameEvent).collect { frame ->
                if (frame.computer.id != state.value.selected) return@collect
                state.update { s -> s.copy(frame = if (frame.screen?.screenshot == null) s.frame?.copy(computer = frame.computer, message = frame.message) ?: frame else frame,
                    computers = s.computers?.map { if (it.id == frame.computer.id) frame.computer else it }) }
            }
        }
    }

    /** Loads the Computers again, e.g. after loading them failed. */
    fun retry() {
        state.update { it.copy(error = null) }
        viewModelScope.launch { watching = null; load() }
    }

    fun select(id: String) {
        if (id == state.value.selected) return
        state.update { it.copy(selected = id, frame = null, error = null) }
        viewModelScope.launch { attempt { watch() } }
    }

    fun takeOver() = act { client.call(Api.Takeover, TakeoverParams(it, true)) }

    fun handBack() = act { client.call(Api.Takeover, TakeoverParams(it, false)) }

    /** [x] and [y] in the frame's pixels. */
    fun tap(x: Int, y: Int) = input(ComputerAction(ComputerActionKind.Tap, x = x, y = y))

    fun swipe(x: Int, y: Int, toX: Int, toY: Int) = input(ComputerAction(ComputerActionKind.Swipe, x = x, y = y, toX = toX, toY = toY))

    fun key(key: String) = input(ComputerAction(ComputerActionKind.Key, key = key))

    fun type(text: String) { if (text.isNotEmpty()) input(ComputerAction(ComputerActionKind.Type, text = text)) }

    private fun input(action: ComputerAction) = act { client.call(Api.ComputerInput, ComputerInputParams(it, action)) }

    private fun act(call: suspend (String) -> ComputerFrame) {
        val id = state.value.selected ?: return
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            try {
                val frame = owner.run { call(id) }
                state.update { s ->
                    // A late answer from a Computer no longer shown only updates its entry.
                    val shown = s.selected == id
                    s.copy(frame = if (!shown) s.frame else if (frame.screen != null) frame else s.frame?.copy(computer = frame.computer) ?: frame,
                        computers = s.computers?.map { if (it.id == id) frame.computer else it }, error = if (shown) frame.message else s.error)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { if (it.selected == id) it.copy(error = e.message ?: e.toString()) else it }
            }
            state.update { it.copy(busy = false) }
        }
    }

    private fun update(all: List<ComputerInfo>) = state.update { s ->
        // Only the Computer the route opened is watched; without one the list shows.
        val selected = s.selected?.takeIf { id -> all.any { it.id == id } }
        if (selected == s.selected) s.copy(computers = all) else s.copy(computers = all, selected = selected, frame = null, error = null)
    }

    private suspend fun load() = attempt {
        update(client.call(Api.Computers, Unit))
        watch()
    }

    private suspend fun watch() = watchLock.withLock {
        val id = state.value.selected
        if (id == watching) return@withLock
        watching?.let { client.call(Api.WatchComputer, WatchComputerParams(it, false)) }
        watching = id
        id?.let { client.call(Api.WatchComputer, WatchComputerParams(it, true)) }
    }

    override fun onCleared() {
        val id = watching ?: return
        // The Server also stops the frames when this client disconnects.
        owner.scope.launch { runCatching { client.call(Api.WatchComputer, WatchComputerParams(id, false)) } }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            owner.run { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }
}
