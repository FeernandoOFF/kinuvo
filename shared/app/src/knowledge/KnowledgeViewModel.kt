package kinuvo.app.knowledge

import kinuvo.app.connection.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.protocol.Note
import kinuvo.protocol.NoteSummary
import kinuvo.protocol.KnowledgeChange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [results] match [query]; [changes] are the latest ones Agents made (null until loaded); [note] is the open one. */
data class KnowledgeState(
    val route: Route.Knowledge,
    val query: String = "",
    val results: List<NoteSummary> = emptyList(),
    val changes: List<KnowledgeChange>? = null,
    val note: Note? = null,
    val errors: List<String> = emptyList(),
    /** Shows only this Agent's [changes]; null for every Agent's. */
    val agent: String? = null,
) {
    val shownChanges get() = changes?.filter { agent == null || it.agentId == agent }
}

class KnowledgeViewModel(private val repo: KnowledgeRepository, route: Route.Knowledge) : ViewModel() {
    val state: StateFlow<KnowledgeState>
        field = MutableStateFlow(KnowledgeState(route))
    private var searching: Job? = null

    fun filter(agent: String?) = state.update { it.copy(agent = agent) }

    init {
        repo.connection.bind(viewModelScope)
        repo.load()
        viewModelScope.launch { repo.changes.collect { c -> state.update { it.copy(changes = c) } } }
        route.note?.let { ref -> attempt { state.update { it.copy(note = repo.note(ref)) } } }
    }

    /** Searches as the user types, once they pause. */
    fun search(query: String) {
        state.update { it.copy(query = query) }
        searching?.cancel()
        if (query.isBlank()) return state.update { it.copy(results = emptyList()) }
        searching = viewModelScope.launch {
            delay(250)
            catching { repo.search(query) }.onSuccess { r -> state.update { it.copy(results = r) } }
        }
    }

    fun undo(changeId: String) = attempt {
        val undone = repo.undo(changeId)
        val open = state.value.note?.ref
        if (open == undone.ref) {
            state.update { it.copy(note = null) }
            val note = repo.note(open)
            state.update { it.copy(note = note) }
        }
    }

    private fun attempt(block: suspend () -> Unit) {
        state.update { it.copy(errors = emptyList()) }
        viewModelScope.launch {
            try {
                repo.connection.run { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(errors = it.errors + (e.message ?: e.toString())) }
            }
        }
    }
}
