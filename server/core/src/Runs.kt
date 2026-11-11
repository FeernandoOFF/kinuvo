package kinuvo.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.Agent
import kinuvo.harness.BUILT_IN_HARNESS
import kinuvo.harness.builtInRegistration
import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionFactory
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionSink
import kinuvo.harness.ExecutionState
import kinuvo.harness.RunKind
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.RunId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/** How many runs are going and how many events they've produced, e.g. for a host keeping them alive in the background. */
data class RunActivity(val runs: Int = 0, val steps: Long = 0)

/** The active runs, at most one per Session. They belong to the server: they outlive the client that started them. */
class Runs(private val log: SessionLog, private val scope: CoroutineScope, private val factory: ExecutionFactory) {
    class Run(val id: RunId, val job: Job)

    val activity: StateFlow<RunActivity>
        field = MutableStateFlow(RunActivity())

    /** The run was stopped while it was being set up, so nothing ran; its onDone has been called. */
    class StoppedBeforeStart : IllegalStateException("Stopped before it started")

    private val active = ConcurrentMutableMap<SessionId, Job>()
    /** The Routine behind each active run a Routine started, by Session. */
    private val routines = ConcurrentMutableMap<SessionId, RoutineRef>()
    /** Agents that can't start runs, e.g. while being deleted. */
    private val closed = mutableSetOf<AgentId>()

    /** Stops [agent] from starting runs, unless one of [sessionIds] has a run going; returns whether it closed. */
    fun close(agent: Agent, sessionIds: Collection<SessionId>): Boolean = active.block { runs ->
        sessionIds.none(runs::containsKey).also { if (it) closed += agent.id }
    }

    fun reopen(agentId: AgentId) = active.block { closed -= agentId }

    /** Whether any of [sessionIds] has a run going. */
    fun anyActive(sessionIds: Collection<SessionId>) = sessionIds.any(active::containsKey)

    fun isActive(sessionId: SessionId) = active.containsKey(sessionId)

    /** The Routine that started the active run in [sessionId]; null for the user's runs. */
    fun routine(sessionId: SessionId): RoutineRef? = routines[sessionId]

    /**
     * Starts a run of [input] in [sessionId], or fails when one is already active there. [onEvent] sees every event of the
     * run; [onDone] runs once it ends, [stopped] or not, while the Session is still reserved. [reserved] runs once the
     * Session is reserved, before the run is set up.
     */
    suspend fun start(
        agent: Agent,
        sessionId: SessionId,
        input: ExecutionInput,
        queued: Boolean = false,
        reserved: suspend () -> Unit = {},
        onEvent: suspend (AgentEvent) -> Unit = {},
        onDone: suspend (stopped: Boolean) -> Unit = {},
    ): Run {
        // Reserve the Session before anything suspends, so two sends can't both start a run in it.
        val reservation = Job()
        while (true) {
            val previous = active.block { runs ->
                check(agent.id !in closed) { "Agent '${agent.id}' is being deleted" }
                val previous = runs[sessionId]
                check(queued || previous == null) { "A run is already active in this session" }
                if (previous == null) runs[sessionId] = reservation
                previous
            }
            if (previous == null) break
            previous.join()
        }
        // Counted from the reservation, while the session is still being opened; completing it releases the count once.
        val counted = Job().also { activity.update { a -> a.copy(runs = a.runs + 1) } }
        counted.invokeOnCompletion { activity.update { a -> a.copy(runs = a.runs - 1) } }
        try {
            reserved()
            val sink = object : ExecutionSink {
                override suspend fun publish(event: AgentEvent) {
                    onEvent(event); log.publish(agent.id, sessionId, event); activity.update { a -> a.copy(steps = a.steps + 1) }
                }
                override suspend fun commit(entries: List<SessionEntry>) { log.appendAll(agent.id, sessionId, entries) }
            }
            val state = ExecutionState(sessionId, sink = sink)
            val session = factory.open(BUILT_IN_HARNESS, builtInRegistration(agent), state, ExecutionBroker(state), scope)
            val runId = input.runId ?: RunId(Uuid.random().toString())
            if (!reservation.isActive) stoppedEarly(sessionId, reservation, onDone)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var stopped = false
                try {
                    session.prompt(input.copy(runId = runId))
                } catch (e: CancellationException) {
                    stopped = true
                    throw e
                } finally {
                    withContext(NonCancellable) { try { session.close() } finally { onDone(stopped) } }
                }
            }
            (input.kind as? RunKind.Routine)?.let { routines[sessionId] = it.turn.ref }
            job.invokeOnCompletion { routines.remove(sessionId); active.removeExact(sessionId, job); counted.complete() }
            val installed = active.block { runs -> (reservation.isActive && runs[sessionId] == reservation).also { if (it) runs[sessionId] = job } }
            if (!installed) {
                job.cancel()
                stoppedEarly(sessionId, reservation, onDone)
            }
            job.start()
            return Run(runId, job)
        } catch (e: Throwable) {
            active.removeExact(sessionId, reservation)
            counted.complete()
            throw e
        }
    }

    private suspend fun stoppedEarly(sessionId: SessionId, reservation: Job, onDone: suspend (Boolean) -> Unit): Nothing {
        try { onDone(true) } finally { active.removeExact(sessionId, reservation) }
        throw StoppedBeforeStart()
    }

    fun stop(sessionId: SessionId) = active.block { it[sessionId]?.cancel() }

    fun sessions(): Set<SessionId> = active.block { it.keys.toSet() }

    /** Waits for the active run in [sessionId], if any. */
    suspend fun join(sessionId: SessionId) = active[sessionId]?.join()

    /** Stops every run and waits for them to save their partial output. */
    suspend fun stopAll() {
        val all = active.block { it.values.toSet() }
        all.forEach { it.cancel() }
        all.joinAll()
    }
}

/** Removes [key] only while it still maps to [value]. */
internal fun <K, V> ConcurrentMutableMap<K, V>.removeExact(key: K, value: V) = block { map -> (map[key] == value).also { if (it) map.remove(key) } }
