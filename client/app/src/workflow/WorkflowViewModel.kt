package kinuvo.app.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kinuvo.protocol.workflow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkflowState(val workflow: Workflow? = null, val latestRun: WorkflowRun? = null,
    val activeRun: WorkflowRun? = null, val steps: Map<StepId, StepRun> = emptyMap(),
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null)

class WorkflowViewModel(private val repo: WorkflowRepository, private val workflowId: WorkflowId) : ViewModel() {
    val state: StateFlow<WorkflowState>
        field = MutableStateFlow(WorkflowState())

    init {
        repo.connection.bind(viewModelScope)
        repo.load(workflowId)
        viewModelScope.launch { repo.state.collect { data ->
            val history = data.runs[workflowId].orEmpty()
            val active = history.firstOrNull { it.status == WorkflowRunStatus.Running }
            val latest = history.firstOrNull()
            state.update { it.copy(workflow = data.workflows.find { it.id == workflowId }, latestRun = latest,
                activeRun = active, steps = (active ?: latest)?.steps.orEmpty(), loading = data.loading, error = data.error ?: it.error) }
        } }
    }

    fun refresh() = repo.load(workflowId)
    fun saveGraph(workflow: Workflow) = attempt {
        require(workflow.id == workflowId)
        repo.save(workflow, create = state.value.workflow == null)
    }
    fun run() = attempt { repo.run(workflowId) }
    fun cancel() = attempt { state.value.activeRun?.let { repo.cancel(it.id) } }
    fun approve(stepId: StepId, allow: Boolean = true) = attempt {
        val step = requireNotNull(state.value.steps[stepId])
        check(repo.approve(step, allow)) { "This Approval is no longer waiting" }
    }
    private fun attempt(block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(saving = true, error = null) }
        catching { repo.connection.run { block() } }
            .onFailure { error -> state.update { it.copy(error = error.message) } }
        state.update { it.copy(saving = false) }
    }
}
