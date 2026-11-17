package kinuvo.server

import kinuvo.protocol.tool.ToolNames
import kinuvo.server.tools.ToolScope
import kinuvo.protocol.agent.AgentId
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.Agent
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ToolTurn
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.uuid.Uuid

sealed class WorkflowError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val id: String) : WorkflowError(WireErrorCode.NotFound, "Workflow or run '$id' not found")
    data class Conflict(val reason: String) : WorkflowError(WireErrorCode.Conflict, reason)
    data class Invalid(val reason: String) : WorkflowError(WireErrorCode.InvalidParams, reason)
}

/** Durable graph snapshots and results; runs resume after a restart, but interrupted Tool and Agent work is never replayed. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class Workflows internal constructor(
    private val harness: AgentRuntime, private val runs: Runs, private val scope: CoroutineScope,
    private val changed: (List<Workflow>) -> Unit = {}, private val runChanged: (WorkflowRun) -> Unit = {},
    private val wakeScheduler: () -> Unit = {},
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var data = WorkflowData()
    private val jobs = mutableMapOf<WorkflowRunId, Job>()
    private val attentionLock = Mutex()
    private val announced = mutableMapOf<WorkflowRunId, Pair<AgentId, SessionId>>()

    /** Unfinished runs keep their state except interrupted Tool and Agent steps, which may have had side effects and fail instead of replaying. */
    suspend fun recover() = lock.withLock {
        data = harness.store.workflows()
        val before = data.runs.filter { it.status == WorkflowRunStatus.Running }.map { it.id }.toSet()
        data = data.copy(runs = data.runs.map { r -> if (r.status != WorkflowRunStatus.Running) r else {
            val steps = r.steps.mapValues { (id, s) -> when {
                s.status in terminal -> s
                s.status == StepStatus.WaitingApproval -> s.copy(status = StepStatus.Pending, interactionId = null)
                s.status != StepStatus.Running -> s
                r.workflow.steps.find { it.id == id } is WorkflowStep.Model -> s.copy(status = StepStatus.Pending)
                else -> s.copy(status = StepStatus.Failed, error = "Interrupted by a Server restart")
            } }
            if (steps.values.none { it.status == StepStatus.Failed }) r.copy(steps = steps)
            else r.copy(status = WorkflowRunStatus.Failed, endedAt = clock(), steps = steps.mapValues { (_, s) -> if (s.status in terminal) s else s.copy(status = StepStatus.Skipped) })
        } })
        persist()
        data.runs.filter { it.id in before }.forEach(runChanged)
    }

    /** Executes recovered Running runs again from where they stopped; called once the Server's listeners are in place. */
    suspend fun resume() = lock.withLock {
        for (r in data.runs.filter { it.status == WorkflowRunStatus.Running && it.id !in jobs }) jobs[r.id] = scope.launch(start = CoroutineStart.ATOMIC) { execute(r) }
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
        if (workflow.steps.any { it is WorkflowStep.Tool || it is WorkflowStep.Model } && workflow.agentId == null) raise(WorkflowError.Invalid("Tool and Model steps need the Agent the Workflow runs as"))
        if (workflow.agentId != null && workflow.agentId !in agents) raise(WorkflowError.Invalid("Unknown Agent"))
        return lock.withLock {
            val old = data.workflows.find { it.id == workflow.id }
            if (create && old != null) raise(WorkflowError.Conflict("Workflow already exists"))
            if (!create && old == null) raise(WorkflowError.NotFound(workflow.id.value))
            val saved = workflow.copy(createdAt = old?.createdAt ?: clock())
            data = data.copy(workflows = data.workflows.filterNot { it.id == saved.id } + saved,
                next = if (old != null && old.trigger == saved.trigger && old.timeZone == saved.timeZone && saved.id in data.next) data.next
                    else data.next + (saved.id to ((saved.trigger as? Trigger.Once)?.at ?: next(saved, clock() - 1))))
            persist(); changed(data.workflows); wakeScheduler(); saved
        }
    }

    context(_: Raise<WorkflowError>)
    suspend fun delete(id: WorkflowId) = lock.withLock {
        find(id)
        if (data.runs.any { it.workflowId == id && it.status == WorkflowRunStatus.Running }) raise(WorkflowError.Conflict("Cancel the active run first"))
        data = data.copy(workflows = data.workflows.filterNot { it.id == id }, next = data.next - id, sessions = data.sessions - id)
        persist(); changed(data.workflows)
    }

    context(_: Raise<WorkflowError>)
    suspend fun run(id: WorkflowId, input: JsonObject = JsonObject(emptyMap())): WorkflowRun = lock.withLock {
        val workflow = find(id)
        if (data.runs.any { it.workflowId == id && it.status == WorkflowRunStatus.Running }) raise(WorkflowError.Conflict("Workflow is already running"))
        start(workflow, input)
    }

    context(_: Raise<WorkflowError>)
    suspend fun runOf(id: WorkflowRunId): WorkflowRun = lock.withLock { data.runs.find { it.id == id } ?: raise(WorkflowError.NotFound(id.value)) }

    /** A new run of a failed or cancelled run's graph and input that keeps its Done steps and executes the rest. */
    context(_: Raise<WorkflowError>)
    suspend fun retry(id: WorkflowRunId): WorkflowRun = lock.withLock {
        val old = data.runs.find { it.id == id } ?: raise(WorkflowError.NotFound(id.value))
        find(old.workflowId)
        if (old.status != WorkflowRunStatus.Failed && old.status != WorkflowRunStatus.Cancelled) raise(WorkflowError.Conflict("Only a failed or cancelled run can be retried"))
        if (data.runs.any { it.workflowId == old.workflowId && it.status == WorkflowRunStatus.Running }) raise(WorkflowError.Conflict("Workflow is already running"))
        start(old.workflow, old.input, old)
    }

    /** Each declared input's string default under what the run was given. */
    private fun input(workflow: Workflow, given: JsonObject) = JsonObject(workflow.inputs.associate { it.name to inputValue(it.default) } + given)

    private suspend fun start(workflow: Workflow, given: JsonObject = JsonObject(emptyMap()), retryOf: WorkflowRun? = null): WorkflowRun {
        val r = WorkflowRun(WorkflowRunId(Uuid.random().toString()), workflow.id, WorkflowRunStatus.Running, clock(),
            steps = workflow.steps.associate { step -> step.id to (retryOf?.steps?.get(step.id)?.let { old -> if (old.status == StepStatus.Done) old else StepRun(sessionId = old.sessionId) } ?: StepRun()) },
            workflow = workflow, input = if (retryOf != null) given else input(workflow, given), retryOf = retryOf?.id)
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
            Routine(w.id.value, w.agentId ?: w.steps.filterIsInstance<WorkflowStep.AgentTurn>().firstOrNull()?.agentId
                ?: AgentId("default"), w.name, "", trigger, w.timeZone, createdAt = w.createdAt), after)
    }

    private suspend fun execute(initial: WorkflowRun) {
        var status = WorkflowRunStatus.Done
        try {
            coroutineScope {
                val tasks = mutableMapOf<StepId, Deferred<Unit>>()
                val ordered = order(initial.workflow)
                for (step in ordered) {
                    val incoming = initial.workflow.edges.filter { it.to == step.id }
                    val predecessors = incoming.map { tasks.getValue(it.from) }
                    tasks[step.id] = if (initial.steps[step.id]?.status == StepStatus.Done) async {} else async {
                        predecessors.awaitAll()
                        if (incoming.isEmpty() || live(initial.id, incoming)) step(initial, step) else update(initial.id, step.id, StepRun(StepStatus.Skipped))
                    }
                }
                tasks.values.awaitAll()
            }
        } catch (e: CancellationException) { status = WorkflowRunStatus.Cancelled; throw e }
        catch (_: Exception) { status = WorkflowRunStatus.Failed }
        finally { withContext(NonCancellable) {
            lock.withLock {
                val current = data.runs.first { it.id == initial.id }
                val steps = current.steps.mapValues { (_, s) ->
                    if (s.status in terminal) s else s.copy(status = if (status == WorkflowRunStatus.Cancelled) StepStatus.Cancelled else StepStatus.Skipped, interactionId = null)
                }
                val finished = current.copy(status = status, endedAt = clock(), steps = steps, output = if (status == WorkflowRunStatus.Done) output(current.workflow, steps) else null)
                data = data.copy(runs = data.runs.map { if (it.id == finished.id) finished else it }); jobs.remove(initial.id)
                persist(); runChanged(finished)
            }
            attentionLock.withLock { announced.remove(initial.id) }?.let { (agent, session) -> note(agent, session, "Run ${status.name.lowercase()}") }
        } }
    }

    /** An edge is followed when its source is Done and, out of a Condition, its branch is the Condition's result. */
    private suspend fun live(id: WorkflowRunId, incoming: List<WorkflowEdge>) = lock.withLock {
        val steps = data.runs.first { it.id == id }.steps
        incoming.any { e -> steps[e.from]?.let { it.status == StepStatus.Done && (e.branch == null || it.output == e.branch.toString()) } == true }
    }

    /** What the run produced: the output of its last step, or of each of them by id when several branches end. */
    private fun output(w: Workflow, steps: Map<StepId, StepRun>): String? {
        val ends = w.steps.filter { s -> w.edges.none { it.from == s.id } }.mapNotNull { s -> steps[s.id]?.takeIf { it.status == StepStatus.Done }?.output?.let { s.id to it } }
        return when (ends.size) {
            0 -> null
            1 -> ends.single().second
            else -> JsonObject(ends.associate { (id, text) -> id.value to (WorkflowReferences.json(text) ?: JsonPrimitive(text)) }).toString()
        }
    }

    private suspend fun step(run: WorkflowRun, step: WorkflowStep) {
        var session = run.steps[step.id]?.sessionId
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
                    val agent = sessionAgent(run)
                    session = attention(run, agent, session)
                    val text = render(run.id, step.text)
                    val pending = harness.approvals.register()
                    try {
                        val interaction = PendingInteraction.Approval(pending.id, ToolCallId(Uuid.random().toString()), "workflow_approval", "{}",
                            reason = text, trustable = false, preview = ChangePreview("Continue Workflow", body = text))
                        harness.log.publish(agent.id, session, AgentEvent.InteractionRequested(interaction))
                        update(run.id, step.id, StepRun(StepStatus.WaitingApproval, session, interactionId = pending.id))
                        val allowed = pending.await() !is ApprovalDecision.Deny
                        note(agent, session, "${if (allowed) "Allowed" else "Denied"}${text.quoted()}")
                        if (!allowed) error("Workflow Approval rejected")
                        update(run.id, step.id, StepRun(StepStatus.Done, session))
                    } finally {
                        pending.close()
                        withContext(NonCancellable) { harness.log.publish(agent.id, session, AgentEvent.InteractionResolved(pending.id)) }
                    }
                }
                is WorkflowStep.Review -> {
                    val agent = sessionAgent(run)
                    session = attention(run, agent, session)
                    val form = ReviewForm.of(render(run.id, step.value))
                    val text = render(run.id, step.text)
                    val pending = harness.questions.register()
                    try {
                        val interaction = PendingInteraction.Questions(pending.id, ToolCallId(Uuid.random().toString()), text.ifBlank { null }, form.questions)
                        harness.log.publish(agent.id, session, AgentEvent.InteractionRequested(interaction))
                        update(run.id, step.id, StepRun(StepStatus.WaitingApproval, session, interactionId = pending.id))
                        val answers = pending.await()
                        val output = answers?.let { runCatching { form.output(it) } }
                        note(agent, session, "Review ${if (output == null) "declined" else if (output.isFailure) "failed" else "submitted"}${text.quoted()}")
                        update(run.id, step.id, StepRun(StepStatus.Done, session, (output ?: error("Review declined")).getOrThrow()))
                    } finally {
                        pending.close()
                        withContext(NonCancellable) { harness.log.publish(agent.id, session, AgentEvent.InteractionResolved(pending.id)) }
                    }
                }
                is WorkflowStep.Tool -> {
                    val agent = runsAs(run)
                    update(run.id, step.id, StepRun(StepStatus.Running, session))
                    val args = WorkflowReferences.render(step.args, outputs(run.id))
                    val surface = step.tool in setOf(ToolNames.SHOW_UI, ToolNames.SHOW_HTML)
                    if (surface) session = attention(run, agent, session)
                    val gate = harness.composer.compose(agent, sessionId = if (surface) session else null,
                        scope = if (surface) ToolScope.only(step.tool) else ToolScope.All, requireModel = false).gate
                    val call = SessionEntry.ToolCall(ToolCallId(Uuid.random().toString()), step.tool, args.toString())
                    var recorded = surface
                    if (surface) harness.log.append(agent.id, session!!, call)
                    var pending: InteractionId? = null
                    val turn = ToolTurn(emit = { event ->
                        when (event) {
                            is AgentEvent.InteractionRequested -> {
                                val id = attention(run, agent, session).also { session = it }
                                if (!recorded) { recorded = true; harness.log.append(agent.id, id, call) }
                                val interaction = event.interaction
                                harness.log.publish(agent.id, id, if (interaction is PendingInteraction.Approval) event.copy(interaction = interaction.copy(trustable = false)) else event)
                                pending = event.interaction.id
                                update(run.id, step.id, StepRun(StepStatus.WaitingApproval, id, interactionId = event.interaction.id))
                            }
                            is AgentEvent.InteractionResolved -> session?.let { id ->
                                pending = null
                                harness.log.publish(agent.id, id, event)
                                update(run.id, step.id, StepRun(StepStatus.Running, id))
                            }
                            else -> Unit
                        }
                    })
                    val result = try { gate.invoke(call, turn) }
                    catch (e: Throwable) {
                        if (recorded) withContext(NonCancellable) { harness.log.append(agent.id, session!!, SessionEntry.ToolResult(call.id, step.tool, if (e is CancellationException) "Cancelled" else e.message.orEmpty(), true)) }
                        throw e
                    }
                    finally { pending?.let { id -> withContext(NonCancellable) { session?.let { harness.log.publish(agent.id, it, AgentEvent.InteractionResolved(id, false)) } } } }
                    if (recorded) harness.log.append(agent.id, session!!, SessionEntry.ToolResult(call.id, step.tool, result.output, result.isError))
                    if (result.isError) error(result.output)
                    update(run.id, step.id, StepRun(StepStatus.Done, session, result.output))
                }
                is WorkflowStep.Condition -> {
                    val refs = outputs(run.id)
                    val result = WorkflowReferences.test(WorkflowReferences.render(step.left, refs), step.op, WorkflowReferences.render(step.right, refs))
                    update(run.id, step.id, StepRun(StepStatus.Done, output = result.toString()))
                }
                is WorkflowStep.Model -> {
                    val agent = runsAs(run)
                    update(run.id, step.id, StepRun(StepStatus.Running))
                    val refs = outputs(run.id)
                    val request = harness.composer.completion(agent, step.system?.let { WorkflowReferences.render(it, refs) }, step.model)
                    val scratch = SessionId(Uuid.random().toString())
                    var failure: String? = null
                    harness.native.runner(request).run(scratch, WorkflowReferences.render(step.prompt, refs)).collect { if (it is AgentEvent.RunError) failure = it.message }
                    failure?.let { error(it) }
                    val output = request.store.entries(request.agent.id, scratch).filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text.orEmpty()
                    update(run.id, step.id, StepRun(StepStatus.Done, output = output))
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { session?.let { runs.stop(it); runs.join(it) }; update(run.id, step.id, StepRun(StepStatus.Cancelled, session)) }
            throw e
        } catch (e: Exception) { update(run.id, step.id, StepRun(StepStatus.Failed, session, error = e.message)); throw e }
    }

    /**
     * The one Session where a Workflow asks for the user, kept across its runs and named after it: Approvals, Reviews and tools'
     * Approvals all land there. A run's first use marks where it starts.
     */
    private suspend fun attention(run: WorkflowRun, agent: Agent, earlier: SessionId? = null): SessionId = attentionLock.withLock {
        val existing = harness.store.sessions(agent.id).map { it.id }
        // A Workflow from before it had a Session of its own adopts the one its step last asked in.
        val known = lock.withLock { data.sessions[run.workflowId] }?.takeIf { it in existing }
        val session = known ?: (earlier?.takeIf { it in existing } ?: harness.newSession(agent).id).also { adopted ->
            harness.log.append(agent.id, adopted, SessionEntry.Details(title = run.workflow.name))
            lock.withLock { data = data.copy(sessions = data.sessions + (run.workflowId to adopted)); persist() }
        }
        if (announced.put(run.id, agent.id to session) == null) note(agent, session, if (run.retryOf != null) "Run retried" else "Run started")
        session
    }

    /** A line in the Workflow's Session that belongs to the run, not to the conversation; the model never sees it. */
    private suspend fun note(agent: Agent, session: SessionId, text: String) = note(agent.id, session, text)
    private suspend fun note(agent: AgentId, session: SessionId, text: String) =
        harness.log.append(agent, session, SessionEntry.Notice("workflow-${Uuid.random()}", WORKFLOW_NOTE, text))
    private fun String.quoted() = if (isBlank()) "" else ": “${trim().take(80)}”"

    private suspend fun outputs(id: WorkflowRunId): WorkflowValues {
        val run = lock.withLock { data.runs.first { it.id == id } }
        return WorkflowValues({ run.steps[it]?.output }, run.input)
    }

    private suspend fun render(id: WorkflowRunId, text: String) = WorkflowReferences.render(text, outputs(id))

    /** The Agent whose Session holds an Approval or Review: the one the Workflow runs as, else its first Agent turn's. */
    private suspend fun sessionAgent(run: WorkflowRun) =
        harness.store.agents().first { a -> (run.workflow.agentId ?: run.workflow.steps.filterIsInstance<WorkflowStep.AgentTurn>().firstOrNull()?.agentId)?.let { it == a.id } ?: true }

    private suspend fun runsAs(run: WorkflowRun) = harness.store.agents().first { it.id == run.workflow.agentId }

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
        check(w.edges.all { e -> e.branch == null || w.steps.first { it.id == e.from } is WorkflowStep.Condition }, "Only an edge out of a Condition step can have a branch")
        val inputs = w.inputs.map { it.name }
        check(inputs.distinct().size == inputs.size && inputs.all { it.matches(Regex("[a-zA-Z0-9_-]+")) }, "Input names must be unique letters, digits, underscores or hyphens")
        check(order(w).size == ids.size, "Workflow contains a cycle")
        try { TimeZone.of(w.timeZone); w.trigger?.let(Schedule::validate) }
        catch (e: IllegalArgumentException) { raise(WorkflowError.Invalid(e.message ?: "Invalid schedule")) }
        for (step in w.steps) {
            val ancestors = mutableSetOf<StepId>()
            fun visit(id: StepId) { w.edges.filter { it.to == id }.forEach { if (ancestors.add(it.from)) visit(it.from) } }
            visit(step.id)
            check(WorkflowReferences.strings(step).all { text -> WorkflowReferences.ids(text).all { it in ancestors } }, "Output references must name predecessor steps")
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
        /** The [SessionEntry.Notice] code of a Workflow run's lines in its Session. */
        const val WORKFLOW_NOTE = "workflow"
        val terminal = setOf(StepStatus.Done, StepStatus.Failed, StepStatus.Skipped, StepStatus.Cancelled)
    }
}
