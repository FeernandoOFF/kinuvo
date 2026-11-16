package kinuvo.app.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.catching
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.model.ModelsCatalog
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [workflow] is the saved graph; [draft] is the graph being edited, and [generation] changes when the editor page must reload it,
 * on discard or when the Server changes the graph. [viewing] picks the run from [runs] shown on the graph, else the active or latest one.
 */
data class WorkflowState(val workflow: Workflow? = null, val draft: Workflow? = null, val generation: Int = 0, val latestRun: WorkflowRun? = null,
    val activeRun: WorkflowRun? = null, val runs: List<WorkflowRun> = emptyList(), val viewing: WorkflowRunId? = null,
    val models: ModelsCatalog = ModelsCatalog(), val tools: List<String> = emptyList(),
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null) {
    val dirty get() = draft != workflow
    val run get() = runs.firstOrNull { it.id == viewing } ?: activeRun ?: latestRun
    val steps: Map<StepId, StepRun> get() = run?.steps.orEmpty()

    /** The Agent and Session of [step] in the shown run. An Agent turn belongs to its own Agent, any other step to the Workflow's Agent, else the first Agent turn's, as on the Server. */
    fun sessionOf(step: StepId): Pair<AgentId, SessionId>? {
        val session = steps[step]?.sessionId ?: return null
        val graph = run?.workflow ?: draft ?: return null
        val turns = graph.steps.filterIsInstance<WorkflowStep.AgentTurn>()
        val agent = turns.firstOrNull { it.id == step }?.agentId ?: graph.agentId ?: turns.firstOrNull()?.agentId ?: return null
        return agent to session
    }
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
            val saved = data.workflows.find { it.id == workflowId }
            state.update {
                val changed = saved != it.workflow
                val reload = changed && it.draft != null && saved != null && (saved.steps != it.draft.steps || saved.edges != it.draft.edges)
                it.copy(workflow = saved, draft = if (changed) saved else it.draft, generation = if (reload) it.generation + 1 else it.generation,
                    latestRun = history.firstOrNull(), activeRun = active, runs = history, loading = data.loading, error = data.error ?: it.error)
            }
        } }
        viewModelScope.launch {
            state.first { it.draft != null }
            catching { repo.connection.run { repo.models() } }.onSuccess { models -> state.update { it.copy(models = models) } }
        }
        viewModelScope.launch {
            state.map { it.draft?.agentId }.distinctUntilChanged().collect { agent ->
                val tools = agent?.let { catching { repo.connection.run { repo.tools(it) } }.getOrNull() }.orEmpty()
                state.update { it.copy(tools = tools) }
            }
        }
    }

    fun refresh() = repo.load(workflowId)
    fun edit(draft: Workflow) = state.update { it.copy(draft = draft) }
    fun discard() = state.update { it.copy(draft = it.workflow, generation = it.generation + 1) }
    fun view(run: WorkflowRunId?) = state.update { it.copy(viewing = run) }
    /** The editor page owns the graph; everything else in the draft stays as the native fields left it. */
    fun graphChanged(edited: Workflow) = state.update { s -> s.draft?.let { s.copy(draft = it.copy(steps = edited.steps, edges = edited.edges)) } ?: s }
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
        val step = requireNotNull(state.value.activeRun?.steps?.get(stepId))
        check(repo.approve(step, allow)) { "This Approval is no longer waiting" }
    }
    private fun attempt(block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(saving = true, error = null) }
        catching { repo.connection.run { block() } }
            .onFailure { error -> state.update { it.copy(error = error.message) } }
        state.update { it.copy(saving = false) }
    }
}
