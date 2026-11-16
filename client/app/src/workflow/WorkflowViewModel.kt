package kinuvo.app.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.workflow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [workflow] is the saved graph; [draft] is the graph being edited, and [generation] changes on discard so text fields drop their edits. */
data class WorkflowState(val workflow: Workflow? = null, val draft: Workflow? = null, val generation: Int = 0, val latestRun: WorkflowRun? = null,
    val activeRun: WorkflowRun? = null, val steps: Map<StepId, StepRun> = emptyMap(),
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null) {
    val dirty get() = draft != workflow
}

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
            val saved = data.workflows.find { it.id == workflowId }
            state.update { it.copy(workflow = saved, draft = if (saved != it.workflow) saved else it.draft, latestRun = latest,
                activeRun = active, steps = (active ?: latest)?.steps.orEmpty(), loading = data.loading, error = data.error ?: it.error) }
        } }
    }

    fun refresh() = repo.load(workflowId)
    fun edit(draft: Workflow) = state.update { it.copy(draft = draft) }
    fun discard() = state.update { it.copy(draft = it.workflow, generation = it.generation + 1) }
    /** Adds a step after [after] and returns its id. */
    fun add(kind: StepKind, agents: List<AgentInfo>, after: WorkflowStep?): StepId {
        val (draft, id) = requireNotNull(state.value.draft).add(kind, agents, after)
        edit(draft)
        return id
    }
    fun remove(id: StepId) = edit(requireNotNull(state.value.draft).remove(id))
    fun save() {
        val draft = state.value.draft ?: return
        attempt {
            require(draft.id == workflowId)
            repo.save(draft, create = state.value.workflow == null)
        }
    }
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch { catching { repo.connection.run { repo.delete(workflowId) } }.onSuccess { onDeleted() } }
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
