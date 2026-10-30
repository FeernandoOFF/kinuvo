package kinuvo.harness.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentRun
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
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
class Runs(private val log: SessionLog, private val scope: CoroutineScope) {
    class Run(val id: String, val job: Job)

    val activity: StateFlow<RunActivity>
        field = MutableStateFlow(RunActivity())

    /** The run was stopped while it was being set up, so nothing ran; its onDone has been called. */
    class StoppedBeforeStart : IllegalStateException("Stopped before it started")

    private val active = ConcurrentMutableMap<String, Job>()
    /** The Routine behind each active run a Routine started, by Session. */
    private val routines = ConcurrentMutableMap<String, RoutineRef>()
    /** Agents that can't start runs, e.g. while being deleted. */
    private val closed = mutableSetOf<String>()

    /** Stops [agent] from starting runs, unless one of [sessionIds] has a run going; returns whether it closed. */
    fun close(agent: Agent, sessionIds: Collection<String>): Boolean = active.block { runs ->
        sessionIds.none(runs::containsKey).also { if (it) closed += agent.id }
    }

    fun reopen(agentId: String) = active.block { closed -= agentId }

    /** Whether any of [sessionIds] has a run going. */
    fun anyActive(sessionIds: Collection<String>) = sessionIds.any(active::containsKey)

    fun isActive(sessionId: String) = active.containsKey(sessionId)

    /** The Routine that started the active run in [sessionId]; null for the user's runs. */
    fun routine(sessionId: String): RoutineRef? = routines[sessionId]

    /**
     * Starts a run in [sessionId], or fails when one is already active there. [onEvent] sees every event of the run;
     * [onDone] runs once it ends, [stopped] or not, while the Session is still reserved.
     */
    suspend fun start(
        agent: Agent,
        sessionId: String,
        message: String,
        activation: SessionEntry.Activation? = null,
        routine: RoutineRef? = null,
        attachments: List<kinuvo.protocol.Attachment> = emptyList(),
        voice: Boolean = false,
        onEvent: (AgentEvent) -> Unit = {},
        onDone: suspend (stopped: Boolean) -> Unit = {},
        runner: suspend () -> AgentRun,
    ): Run {
        // Reserve the Session before anything suspends, so two sends can't both start a run in it.
        val reservation = Job()
        active.block { runs ->
            check(agent.id !in closed) { "Agent '${agent.id}' is being deleted" }
            check(sessionId !in runs) { "A run is already active in this session" }
            runs[sessionId] = reservation
        }
        // Counted from the reservation, while the runner is still being prepared; completing it releases the count once.
        val counted = Job().also { activity.update { a -> a.copy(runs = a.runs + 1) } }
        counted.invokeOnCompletion { activity.update { a -> a.copy(runs = a.runs - 1) } }
        try {
            val r = runner()
            val runId = Uuid.random().toString()
            if (!reservation.isActive) stoppedEarly(sessionId, reservation, onDone)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var stopped = false
                try {
                    r.run(sessionId, message, activation, runId, attachments, voice).collect {
                        onEvent(it); log.publish(agent.id, sessionId, it); activity.update { a -> a.copy(steps = a.steps + 1) }
                    }
                } catch (e: CancellationException) {
                    stopped = true
                    withContext(NonCancellable) { log.publish(agent.id, sessionId, AgentEvent.RunFinished(runId)) }
                    throw e
                } finally {
                    withContext(NonCancellable) { onDone(stopped) }
                }
            }
            routine?.let { routines[sessionId] = it }
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

    private suspend fun stoppedEarly(sessionId: String, reservation: Job, onDone: suspend (Boolean) -> Unit): Nothing {
        try { onDone(true) } finally { active.removeExact(sessionId, reservation) }
        throw StoppedBeforeStart()
    }

    fun stop(sessionId: String) = active.block { it[sessionId]?.cancel() }

    fun sessions(): Set<String> = active.block { it.keys.toSet() }

    /** Waits for the active run in [sessionId], if any. */
    suspend fun join(sessionId: String) = active[sessionId]?.join()

    /** Stops every run and waits for them to save their partial output. */
    suspend fun stopAll() {
        val all = active.block { it.values.toSet() }
        all.forEach { it.cancel() }
        all.joinAll()
    }
}

/** Removes [key] only while it still maps to [value]. */
internal fun <K, V> ConcurrentMutableMap<K, V>.removeExact(key: K, value: V) = block { map -> (map[key] == value).also { if (it) map.remove(key) } }
