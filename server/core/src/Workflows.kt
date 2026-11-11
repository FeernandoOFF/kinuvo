package kinuvo.server

import kinuvo.protocol.agent.AgentId
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.ExecutionInput
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.approval.*
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.session.*
import kinuvo.protocol.workflow.*
import kinuvo.server.routines.Schedule
import kinuvo.server.store.WorkflowData
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.uuid.Uuid

sealed class WorkflowError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val id: String) : WorkflowError(WireErrorCode.NotFound, "Workflow or run '$id' not found")
    data class Conflict(val reason: String) : WorkflowError(WireErrorCode.Conflict, reason)
    data class Invalid(val reason: String) : WorkflowError(WireErrorCode.InvalidParams, reason)
}

/** Durable graph snapshots and results; interrupted execution is never replayed. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class Workflows internal constructor(
    private val harness: HarnessRuntime, private val runs: Runs, private val scope: CoroutineScope,
    private val changed: (List<Workflow>) -> Unit = {}, private val runChanged: (WorkflowRun) -> Unit = {},
    private val wakeScheduler: () -> Unit = {},
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var data = WorkflowData()
    private val jobs = mutableMapOf<WorkflowRunId, Job>()

    suspend fun recover() = lock.withLock {
        data = harness.store.workflows()
        data = data.copy(runs = data.runs.map { r -> if (r.status != WorkflowRunStatus.Running) r else r.copy(
            status = WorkflowRunStatus.Cancelled, endedAt = clock(), steps = r.steps.mapValues { (_, s) ->
                if (s.status in terminal) s else s.copy(status = StepStatus.Cancelled, error = "Server restarted", interactionId = null)
            }) })
        persist()
    }

    suspend fun list() = lock.withLock { data.workflows }
    context(_: Raise<WorkflowError>)
    suspend fun get(id: WorkflowId) = lock.withLock { find(id) }
    context(_: Raise<WorkflowError>)
    suspend fun history(id: WorkflowId) = lock.withLock { find(id); data.runs.filter { it.workflowId == id }.sortedByDescending { it.startedAt } }

    context(_: Raise<WorkflowError>)
    suspend fun save(workflow: Workflow, create: Boolean): Workflow {
        validate(workflow)
        val agents = harness.store.agents().map { it.id }
        if (workflow.steps.filterIsInstance<WorkflowStep.AgentTurn>().any { it.agentId !in agents }) raise(WorkflowError.Invalid("Unknown Agent"))
        return lock.withLock {
            val old = data.workflows.find { it.id == workflow.id }
            if (create && old != null) raise(WorkflowError.Conflict("Workflow already exists"))
            if (!create && old == null) raise(WorkflowError.NotFound(workflow.id.value))
            val saved = workflow.copy(createdAt = old?.createdAt ?: clock())
            data = data.copy(workflows = data.workflows.filterNot { it.id == saved.id } + saved,
                next = data.next + (saved.id to ((saved.trigger as? Trigger.Once)?.at ?: next(saved, clock() - 1))))
            persist(); changed(data.workflows); wakeScheduler(); saved
        }
    }

    context(_: Raise<WorkflowError>)
    suspend fun delete(id: WorkflowId) = lock.withLock {
        find(id)
        if (data.runs.any { it.workflowId == id && it.status == WorkflowRunStatus.Running }) raise(WorkflowError.Conflict("Cancel the active run first"))
        data = data.copy(workflows = data.workflows.filterNot { it.id == id }, next = data.next - id)
        persist(); changed(data.workflows)
    }

    context(_: Raise<WorkflowError>)
    suspend fun run(id: WorkflowId): WorkflowRun = lock.withLock {
        val workflow = find(id)
        if (data.runs.any { it.workflowId == id && it.status == WorkflowRunStatus.Running }) raise(WorkflowError.Conflict("Workflow is already running"))
        start(workflow)
    }

    private suspend fun start(workflow: Workflow): WorkflowRun {
        val r = WorkflowRun(WorkflowRunId(Uuid.random().toString()), workflow.id, WorkflowRunStatus.Running, clock(),
            steps = workflow.steps.associate { it.id to StepRun() }, workflow = workflow)
        data = data.copy(runs = data.runs + r); persist(); runChanged(r)
        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(r) }
        jobs[r.id] = job
        return r
    }

    context(_: Raise<WorkflowError>)
    suspend fun cancel(id: WorkflowRunId): WorkflowRun {
        val job = lock.withLock {
            val r = data.runs.find { it.id == id } ?: raise(WorkflowError.NotFound(id.value))
            if (r.status != WorkflowRunStatus.Running) return r
            jobs[id]
        }
        job?.cancelAndJoin()
        return lock.withLock { data.runs.first { it.id == id } }
    }

    /** Invoked by the existing Scheduler; a missed recurring slot fires once, without overlapping runs. */
    suspend fun tick(): Long? = lock.withLock {
        val now = clock()
        for (w in data.workflows.filter { it.enabled && it.trigger != null }) {
            val due = data.next[w.id] ?: continue
            if (due > now) continue
            data = data.copy(next = data.next + (w.id to next(w, now)))
            if (w.trigger is Trigger.Once) data = data.copy(workflows = data.workflows.map { if (it.id == w.id) it.copy(enabled = false) else it })
            if (data.runs.none { it.workflowId == w.id && it.status == WorkflowRunStatus.Running }) start(w) else persist()
            changed(data.workflows)
        }
        data.workflows.filter { it.enabled }.mapNotNull { data.next[it.id] }.minOrNull()
    }

    private fun next(w: Workflow, after: Long): Long? = w.trigger?.let { trigger ->
        if (trigger is Trigger.Once) trigger.at.takeIf { it > after } else Schedule.next(
            Routine(w.id.value, w.steps.filterIsInstance<WorkflowStep.AgentTurn>().firstOrNull()?.agentId
                ?: AgentId("default"), w.name, "", trigger, w.timeZone, createdAt = w.createdAt), after)
    }

    private suspend fun execute(initial: WorkflowRun) {
        var status = WorkflowRunStatus.Done
        try {
            coroutineScope {
                val tasks = mutableMapOf<StepId, Deferred<Unit>>()
                val ordered = order(initial.workflow)
                for (step in ordered) {
                    val predecessors = initial.workflow.edges.filter { it.to == step.id }.map { tasks.getValue(it.from) }
                    tasks[step.id] = async { predecessors.awaitAll(); step(initial, step) }
                }
                tasks.values.awaitAll()
            }
        } catch (e: CancellationException) { status = WorkflowRunStatus.Cancelled; throw e }
        catch (_: Exception) { status = WorkflowRunStatus.Failed }
        finally { withContext(NonCancellable) {
            lock.withLock {
                val current = data.runs.first { it.id == initial.id }
                val finished = current.copy(status = status, endedAt = clock(), steps = current.steps.mapValues { (_, s) ->
                    if (s.status in terminal) s else s.copy(status = if (status == WorkflowRunStatus.Cancelled) StepStatus.Cancelled else StepStatus.Skipped, interactionId = null)
                })
                data = data.copy(runs = data.runs.map { if (it.id == finished.id) finished else it }); jobs.remove(initial.id)
                persist(); runChanged(finished)
            }
        } }
    }

    private suspend fun step(run: WorkflowRun, step: WorkflowStep) {
        var session: SessionId? = null
        try {
            when (step) {
                is WorkflowStep.AgentTurn -> {
                    val agent = harness.store.agents().first { it.id == step.agentId }
                    val previous = if (step.session == StepSession.Continue) lock.withLock {
                        data.runs.filter { it.id != run.id && it.workflowId == run.workflowId && it.status != WorkflowRunStatus.Running }
                            .maxByOrNull { it.startedAt }?.steps?.get(step.id)?.sessionId
                    } else null
                    session = previous ?: harness.newSession(agent).id
                    update(run.id, step.id, StepRun(StepStatus.Running, session))
                    val prompt = render(run.id, step.prompt)
                    var failure: String? = null
                    var stopped = false
                    val active = runs.start(agent, session, ExecutionInput(prompt), onEvent = { if (it is AgentEvent.RunError) failure = it.message }, onDone = { stopped = it })
                    active.job.join()
                    if (stopped) error("Step Session was cancelled")
                    failure?.let { error(it) }
                    val output = harness.store.entries(agent.id, session).takeLastWhile { it !is SessionEntry.UserMessage }
                        .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text.orEmpty()
                    update(run.id, step.id, StepRun(StepStatus.Done, session, output))
                }
                is WorkflowStep.Approval -> {
                    val agent = harness.store.agents().first { a -> run.workflow.steps.filterIsInstance<WorkflowStep.AgentTurn>().firstOrNull()?.agentId?.let { it == a.id } ?: true }
                    session = harness.newSession(agent).id
                    val pending = harness.approvals.register()
                    try {
                        val interaction = PendingInteraction.Approval(pending.id, ToolCallId(Uuid.random().toString()), "workflow_approval", "{}",
                            reason = step.text, trustable = false, preview = ChangePreview("Continue Workflow", body = step.text))
                        harness.log.publish(agent.id, session, AgentEvent.InteractionRequested(interaction))
                        update(run.id, step.id, StepRun(StepStatus.WaitingApproval, session, interactionId = pending.id))
                        if (pending.await() is ApprovalDecision.Deny) error("Workflow Approval rejected")
                        update(run.id, step.id, StepRun(StepStatus.Done, session))
                    } finally {
                        pending.close()
                        withContext(NonCancellable) { harness.log.publish(agent.id, session, AgentEvent.InteractionResolved(pending.id)) }
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { session?.let { runs.stop(it); runs.join(it) }; update(run.id, step.id, StepRun(StepStatus.Cancelled, session)) }
            throw e
        } catch (e: Exception) { update(run.id, step.id, StepRun(StepStatus.Failed, session, error = e.message)); throw e }
    }

    private suspend fun render(id: WorkflowRunId, prompt: String): String = lock.withLock {
        val run = data.runs.first { it.id == id }
        outputRef.replace(prompt) { match -> run.steps.getValue(StepId(match.groupValues[1])).output.orEmpty() }
    }

    private suspend fun update(id: WorkflowRunId, step: StepId, value: StepRun) = lock.withLock {
        val r = data.runs.first { it.id == id }.let { it.copy(steps = it.steps + (step to value)) }
        data = data.copy(runs = data.runs.map { if (it.id == id) r else it }); persist(); runChanged(r)
    }
    private suspend fun persist() = harness.store.saveWorkflows(data)
    context(_: Raise<WorkflowError>)
    private fun find(id: WorkflowId) = data.workflows.firstOrNull { it.id == id } ?: raise(WorkflowError.NotFound(id.value))

    context(_: Raise<WorkflowError>)
    private fun validate(w: Workflow) {
        fun check(ok: Boolean, message: String) { if (!ok) raise(WorkflowError.Invalid(message)) }
        check(w.id.value.isNotBlank() && w.name.isNotBlank() && w.steps.isNotEmpty(), "Workflow needs an id, name and steps")
        val ids = w.steps.map { it.id }
        check(ids.distinct().size == ids.size && ids.all { it.value.matches(Regex("[a-zA-Z0-9_-]+")) }, "Step ids must be unique letters, digits, underscores or hyphens")
        check(w.edges.all { it.from in ids && it.to in ids }, "Unknown edge endpoint")
        check(order(w).size == ids.size, "Workflow contains a cycle")
        try { TimeZone.of(w.timeZone); w.trigger?.let(Schedule::validate) }
        catch (e: IllegalArgumentException) { raise(WorkflowError.Invalid(e.message ?: "Invalid schedule")) }
        for (step in w.steps.filterIsInstance<WorkflowStep.AgentTurn>()) {
            val ancestors = mutableSetOf<StepId>()
            fun visit(id: StepId) { w.edges.filter { it.to == id }.forEach { if (ancestors.add(it.from)) visit(it.from) } }
            visit(step.id)
            check(outputRef.findAll(step.prompt).all { StepId(it.groupValues[1]) in ancestors }, "Output references must name predecessor steps")
        }
    }

    private fun order(w: Workflow): List<WorkflowStep> {
        val result = mutableListOf<WorkflowStep>()
        val remaining = w.steps.toMutableList()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { s -> w.edges.filter { it.to == s.id }.all { e -> result.any { it.id == e.from } } }
            if (ready.isEmpty()) break
            result += ready; remaining.removeAll(ready)
        }
        return result
    }
    private companion object {
        val terminal = setOf(StepStatus.Done, StepStatus.Failed, StepStatus.Skipped, StepStatus.Cancelled)
        val outputRef = Regex("\\{\\{steps\\.([a-zA-Z0-9_-]+)\\.output}}")
    }
}
