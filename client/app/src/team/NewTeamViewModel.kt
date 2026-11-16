package kinuvo.app.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kinuvo.protocol.team.TeamId
import kinuvo.protocol.team.TeamSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewTeamState(val error: String? = null)

/** Creating a Team; [NewTeamState.error] is the last failure. */
class NewTeamViewModel(private val repository: TeamRepository) : ViewModel() {
    val state: StateFlow<NewTeamState>
        field = MutableStateFlow(NewTeamState())

    init { repository.connection.bind(viewModelScope) }

    fun create(spec: TeamSpec, onCreated: (TeamId) -> Unit) {
        viewModelScope.launch {
            catching { repository.connection.run { repository.create(spec) } }
                .onSuccess { onCreated(it.team.id) }
                .onFailure { error -> state.update { it.copy(error = error.message) } }
        }
    }
}
