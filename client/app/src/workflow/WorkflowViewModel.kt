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
import kotlinx.serialization.json.JsonObject

/**
 * [workflow] is the saved graph; [draft] is the graph being edited, and [generation] changes when the editor page must reload it,
 * on discard or when the Server changes the graph. [viewing] picks the run from [runs] shown on the graph, else the active or latest one.
 */
data class WorkflowState(val workflow: Workflow? = null, val draft: Workflow? = null, val generation: Int = 0, val latestRun: WorkflowRun? = null,
    val activeRun: WorkflowRun? = null, val runs: List<WorkflowRun> = emptyList(), val viewing: WorkflowRunId? = null,
    val models: ModelsCatalog = ModelsCatalog(), val tools: List<String> = emptyList(),
    /** What the user typed for the next run's inputs, by name; an input not in here runs with its default. */
    val inputValues: Map<String, String> = emptyMap(),
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null) {
    /** Where the steps sit is saved as they are moved, so only other edits count as unsaved. */
    val dirty get() = draft?.unplaced() != workflow?.unplaced()
    val run get() = runs.firstOrNull { it.id == viewing } ?: activeRun ?: latestRun
    val steps: Map<StepId, StepRun> get() = run?.steps.orEmpty()
    /** A run that stopped early can start again from the step that stopped it, once nothing else is running. */
    val canRetry get() = activeRun == null && run?.status.let { it == WorkflowRunStatus.Failed || it == WorkflowRunStatus.Cancelled }
    /** The steps the active run waits on the user for, in graph order. */
    val waiting: List<WorkflowStep> get() = activeRun?.let { r -> r.workflow.steps.filter { r.steps[it.id]?.status == StepStatus.WaitingApproval } }.orEmpty()

    /** The Agent and Session of [step] in [of], the shown run unless given. An Agent turn belongs to its own Agent, any other step to the Workflow's Agent, else the first Agent turn's, as on the Server. */
    fun sessionOf(step: StepId, of: WorkflowRun? = run): Pair<AgentId, SessionId>? {
        val session = of?.steps?.get(step)?.sessionId ?: return null
        val graph = of.workflow
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
                // A step that only moved, here or on another Device, leaves the draft and the page as they are.
                val moved = changed && saved != null && it.draft != null && saved.unplaced() == it.workflow?.unplaced()
                val reload = changed && !moved && it.draft != null && saved != null && (saved.steps != it.draft.steps || saved.edges != it.draft.edges)
                it.copy(workflow = saved, draft = if (changed && !moved) saved else it.draft, generation = if (reload) it.generation + 1 else it.generation,
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
    fun discard() = state.update { it.copy(draft = it.workflow, generation = it.generation + 1, inputValues = emptyMap()) }
    fun view(run: WorkflowRunId?) = state.update { it.copy(viewing = run) }
    /** The editor page owns the graph; everything else in the draft stays as the native fields left it. */
    fun graphChanged(edited: Workflow) {
        val before = state.value
        state.update { s -> s.draft?.let { s.copy(draft = it.copy(steps = edited.steps, edges = edited.edges)) } ?: s }
        // Moving steps saves their places straight away, on the saved Workflow so that unsaved edits stay a draft.
        val draft = before.draft ?: return
        val saved = before.workflow ?: return
        if (edited.edges != draft.edges || edited.steps == draft.steps || edited.steps.map { it.placed(null) } != draft.steps.map { it.placed(null) }) return
        val places = edited.steps.associate { it.id to it.position }
        val placed = saved.copy(steps = saved.steps.map { it.placed(places[it.id] ?: it.position) })
        if (placed != saved) viewModelScope.launch {
            catching { repo.connection.run { repo.save(placed) } }.onFailure { error -> state.update { it.copy(error = error.message) } }
        }
    }
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
    fun input(name: String, value: String) = state.update { it.copy(inputValues = it.inputValues + (name to value)) }
    fun run() = attempt { repo.run(workflowId, runInput(state.value.workflow?.inputs.orEmpty(), state.value.inputValues)) }
    fun retry() = attempt { state.value.takeIf { it.canRetry }?.run?.let { state.update { s -> s.copy(viewing = null) }; repo.retry(it.id) } }
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

const val NEW_WORKFLOW = "New Workflow"

/** A Workflow nobody has built on yet: the one Agent turn a new one starts with, still without a prompt. */
val Workflow.blank get() = edges.isEmpty() && (steps.singleOrNull() as? WorkflowStep.AgentTurn)?.prompt?.isBlank() == true

/** A run's input: each declared input's typed text, else its default. */
fun runInput(inputs: List<WorkflowInput>, values: Map<String, String>) = JsonObject(inputs.associate { it.name to inputValue(values[it.name] ?: it.default) })

fun WorkflowStep.placed(at: Position?): WorkflowStep = when (this) {
    is WorkflowStep.AgentTurn -> copy(position = at)
    is WorkflowStep.Approval -> copy(position = at)
    is WorkflowStep.Review -> copy(position = at)
    is WorkflowStep.Tool -> copy(position = at)
    is WorkflowStep.Model -> copy(position = at)
    is WorkflowStep.Condition -> copy(position = at)
    is WorkflowStep.Text -> copy(position = at)
}

/** The Workflow without its editor layout. */
fun Workflow.unplaced() = copy(steps = steps.map { it.placed(null) })
