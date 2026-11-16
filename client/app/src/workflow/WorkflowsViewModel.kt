package kinuvo.app.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.workflow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

data class WorkflowListState(val workflows: List<Workflow> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** The Workflows list, and creating a Workflow with one Agent turn. */
class WorkflowsViewModel(private val repo: WorkflowRepository) : ViewModel() {
    val state: StateFlow<WorkflowListState>
        field = MutableStateFlow(WorkflowListState())

    init {
        repo.connection.bind(viewModelScope)
        repo.load()
        viewModelScope.launch { repo.state.collect { data -> state.update { it.copy(workflows = data.workflows, loading = data.loading, error = data.error) } } }
    }

    fun reload() = repo.load()

    fun create(agent: AgentId, onCreated: (WorkflowId) -> Unit) {
        viewModelScope.launch {
            val workflow = Workflow(WorkflowId(Uuid.random().toString()), "New Workflow", listOf(WorkflowStep.AgentTurn(StepId("agent-1"), agent, "")))
            catching { repo.connection.run { repo.save(workflow, create = true) } }.onSuccess { onCreated(it.id) }
        }
    }
}
