package kinuvo.app.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.protocol.CreateTaskParams
import kinuvo.protocol.UpdateTaskParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [board] mirrors the [BoardRepository]; [showDone] adds the Done column, [adding] opens the new Task form. */
data class BoardState(
    val board: BoardData = BoardData(),
    val showDone: Boolean = false,
    val adding: Boolean = false,
    val errors: List<String> = emptyList(),
)

class BoardViewModel(private val repo: BoardRepository) : ViewModel() {
    val state: StateFlow<BoardState>
        field = MutableStateFlow(BoardState())

    init {
        repo.connection.bind(viewModelScope)
        repo.load()
        viewModelScope.launch { repo.state.collect { b -> state.update { it.copy(board = b) } } }
    }

    fun refresh() = viewModelScope.launch { repo.load().join() }

    fun showDone(show: Boolean) = state.update { it.copy(showDone = show) }

    fun adding(open: Boolean) = state.update { it.copy(adding = open, errors = emptyList()) }

    fun add(params: CreateTaskParams) = attempt {
        repo.create(params)
        state.update { it.copy(adding = false) }
    }

    fun update(params: UpdateTaskParams) = attempt { repo.update(params) }

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
