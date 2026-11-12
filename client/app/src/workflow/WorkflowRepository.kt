package kinuvo.app.workflow

import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.catching
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.approval.*
import kinuvo.protocol.workflow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkflowsState(val workflows: List<Workflow> = emptyList(), val runs: Map<WorkflowId, List<WorkflowRun>> = emptyMap(),
    val loading: Boolean = false, val error: String? = null)

class WorkflowRepository(internal val connection: ConnectedServer) {
    val state: StateFlow<WorkflowsState>
        field = MutableStateFlow(WorkflowsState())
    private val requested = mutableSetOf<WorkflowId>()
    private var attached: Job? = null
    private var loading: Job? = null

    internal fun attach(client: KinuvoClient) {
        attached?.cancel()
        attached = connection.scope.launch {
            launch { client.events(Api.WorkflowsChanged).collect { workflows -> state.update { it.copy(workflows = workflows) } } }
            launch { client.events(Api.WorkflowRunChanged).collect { run ->
                state.update { it.copy(runs = it.runs + (run.workflowId to (it.runs[run.workflowId].orEmpty().filterNot { it.id == run.id } + run).sortedByDescending { it.startedAt })) }
            } }
            client.state.collect { if (it is ConnectionState.Connected) load() }
        }
    }

    internal fun detach() {
        attached?.cancel(); loading?.cancel(); requested.clear(); state.value = WorkflowsState()
    }

    fun load(id: WorkflowId? = null) {
        id?.let { requested += it }
        loading?.cancel()
        loading = connection.scope.launch {
            state.update { it.copy(loading = true, error = null) }
            catching {
                connection.call { client ->
                    val workflows = client.call(Api.Workflows, Unit)
                    val histories = requested.toList().filter { id -> workflows.any { it.id == id } }.associateWith { client.call(Api.WorkflowRuns, it) }
                    state.update { it.copy(workflows = workflows, runs = it.runs + histories, loading = false) }
                }
            }.onFailure { error -> state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    suspend fun save(workflow: Workflow, create: Boolean = false): Workflow = connection.call {
        it.call(if (create) Api.CreateWorkflow else Api.UpdateWorkflow, workflow)
    }
    suspend fun run(id: WorkflowId) = connection.call { it.call(Api.RunWorkflow, id) }
    suspend fun cancel(id: WorkflowRunId) = connection.call { it.call(Api.CancelWorkflow, id) }
    suspend fun delete(id: WorkflowId) = connection.call { it.call(Api.DeleteWorkflow, id) }
    suspend fun approve(step: StepRun, allow: Boolean): Boolean = connection.call {
        it.call(Api.Respond, RespondParams(requireNotNull(step.sessionId), requireNotNull(step.interactionId),
            InteractionAnswer.Approval(if (allow) ApprovalDecision.AllowOnce else ApprovalDecision.Deny())))
    }
}
