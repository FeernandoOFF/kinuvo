package kinuvo.app.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.navigation.Route
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BrowserState(val tabs: List<BrowserTab> = emptyList(), val selected: String? = null,
    val frame: BrowserFrame? = null, val busy: Boolean = false, val loaded: Boolean = false, val error: String? = null) {
    val tab get() = tabs.firstOrNull { it.id == selected }
    fun listed(all: List<BrowserTab>): BrowserState {
        val next = selected?.takeIf { id -> all.any { it.id == id } } ?: all.firstOrNull()?.id
        return copy(tabs = all, selected = next, frame = frame.takeIf { it?.tab?.id == next }, loaded = true)
    }
}

class BrowserViewModel(route: Route.Browser, connection: ConnectedServer) : ViewModel() {
    private val owner = BrowserOwner(route.agentId, route.sessionId)
    private val repository = connection.browser
    private val lock = Mutex()
    val state: StateFlow<BrowserState>
        field = MutableStateFlow(BrowserState())

    init {
        connection.bind(viewModelScope)
        viewModelScope.launch {
            repository.changes().collect { if (it == owner) attempt { lock.withLock { refresh() } } }
        }
        viewModelScope.launch {
            while (isActive) {
                attempt {
                    lock.withLock {
                        refresh()
                        val id = state.value.selected
                        if (id != null) show(repository.command(BrowserCommand(owner, BrowserAction.Capture, id)))
                        state.update { it.copy(error = null) }
                    }
                }
                delay(750)
            }
        }
    }

    fun select(id: String) { state.update { it.copy(selected = id, frame = null, error = null) } }
    fun open(url: String) = act(BrowserCommand(owner, BrowserAction.Open, text = url.ifBlank { "about:blank" }))
    fun navigate(url: String) = input(BrowserAction.Navigate, text = url)
    fun takeover(take: Boolean) = input(BrowserAction.Takeover, take = take)
    fun closeTab() = input(BrowserAction.Close)
    fun tap(x: Int, y: Int) = input(BrowserAction.Click, x = x, y = y)
    fun scroll(pixels: Int) = input(BrowserAction.Scroll, y = pixels)
    fun type(text: String) = input(BrowserAction.Type, text = text)
    fun key(key: String) = input(BrowserAction.Key, text = key)

    private fun input(action: BrowserAction, text: String? = null, x: Int? = null, y: Int? = null, take: Boolean? = null) {
        val id = state.value.selected ?: return
        act(BrowserCommand(owner, action, id, text = text, x = x, y = y, take = take))
    }

    private fun act(command: BrowserCommand) {
        viewModelScope.launch {
            state.update { it.copy(busy = true, error = null) }
            try {
                attempt {
                    lock.withLock {
                        val frame = repository.command(command)
                        if (command.action == BrowserAction.Open) state.update { it.copy(selected = frame?.tab?.id) }
                        show(frame)
                        refresh()
                    }
                }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    private suspend fun refresh() { val all = repository.tabs(owner); state.update { it.listed(all) } }
    private fun show(frame: BrowserFrame?) {
        if (frame == null) return
        state.update { s -> if (frame.tab.id == s.selected) s.copy(frame = frame, tabs = s.tabs.map { if (it.id == frame.tab.id) frame.tab else it }) else s }
    }
    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.update { it.copy(error = e.message ?: "Browser unavailable") } }
    }
}
