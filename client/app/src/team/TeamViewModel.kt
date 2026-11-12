package kinuvo.app.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.team.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TeamViewModel(private val repository: TeamRepository, val id: TeamId) : ViewModel() {
    data class State(
        val team: Team? = null,
        val members: List<TeamMember> = emptyList(),
        val items: List<TeamEntry> = emptyList(),
        val runningMember: AgentId? = null,
        val pending: List<TeamInteraction> = emptyList(),
        val draft: String = "",
        val sending: Boolean = false,
        val error: String? = null,
    )
    val state: StateFlow<State>
        field = MutableStateFlow(State())

    init {
        repository.connection.bind(viewModelScope)
        viewModelScope.launch { repository.state.collect { teams ->
            val snapshot = teams[id]
            state.update { it.copy(team = snapshot?.team, members = snapshot?.team?.members.orEmpty(), items = snapshot?.team?.log.orEmpty(), runningMember = snapshot?.runningMember, pending = snapshot?.pending.orEmpty()) }
        } }
        action { repository.load(id) }
    }

    fun draft(text: String) { state.update { it.copy(draft = text) } }
    fun mention(agent: AgentId) { draft(state.value.draft + "@${agent.value} ") }
    fun send() {
        val text = state.value.draft
        if (text.isBlank() || state.value.sending) return
        state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            catching { repository.send(id, text) }
                .onSuccess { state.update { it.copy(draft = if (it.draft == text) "" else it.draft) } }
                .onFailure { e -> state.update { it.copy(error = e.message) } }
            state.update { it.copy(sending = false) }
        }
    }
    fun stop() = action { repository.stop(id) }
    fun respond(request: InteractionId, answer: InteractionAnswer) = action { check(repository.respond(id, request, answer)) { "Interaction is no longer pending" } }
    fun update(spec: TeamSpec) = action { repository.update(id, spec) }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        catching { block() }.onFailure { e -> state.update { it.copy(error = e.message) } }
    } }
}
