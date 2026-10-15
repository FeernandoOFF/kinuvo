package kinuvo.harness.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.core.Agent
import kinuvo.harness.core.routines.Schedule
import kinuvo.harness.core.routines.formatTime
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineRan
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.RunStatus
import kinuvo.protocol.SILENT_REPLY
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.Trigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fires due Routines. It sleeps until the soonest next run and wakes early whenever a Routine changes.
 *
 * A recurring slot missed while the server was down still runs once if it's within its grace period (half the gap to
 * the next slot, up to 2 hours); older ones are skipped. A one-shot runs whenever it's noticed. A Routine never runs
 * twice at once: a slot that comes due while its previous run is still going is skipped.
 */
class Scheduler(
    private val harness: HarnessRuntime,
    private val runs: Runs,
    private val scope: CoroutineScope,
    private val onRan: (RoutineRan) -> Unit = {},
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val firing = ConcurrentMutableMap<String, Job>()
    private var loop: Job? = null

    fun start() {
        harness.routines.onChange { _, _ -> wake.trySend(Unit) }
        loop = scope.launch {
            // Runs cut off by a restart never finished.
            for (agent in harness.store.agents()) for (r in harness.routines.list(agent)) {
                if (r.lastStatus == RunStatus.Running) harness.routines.update(agent, r.id) { it.copy(lastStatus = RunStatus.Stopped) }
            }
            while (true) {
                val soonest = tick()
                val wait = soonest?.let { it - clock() } ?: Long.MAX_VALUE
                if (wait > 0) withTimeoutOrNull(wait.coerceAtMost(MAX_SLEEP)) { wake.receive() }
            }
        }
    }

    /** Starts every due run and returns when the next one is due. */
    suspend fun tick(): Long? {
        val now = clock()
        var soonest: Long? = null
        for (agent in harness.store.agents()) for (routine in harness.routines.list(agent)) {
            if (routine.runRequested) {
                harness.routines.update(agent, routine.id) { it.copy(runRequested = false) }
                fire(agent, routine, waitUntil = now + MANUAL_WAIT)
            }
            val due = routine.nextRunAt?.takeIf { routine.enabled } ?: continue
            if (due > now) soonest = minOf(soonest ?: due, due)
            else onDue(agent, routine, due, now)
        }
        return soonest
    }

    /** Waits for every run the scheduler started, e.g. in tests. */
    suspend fun idle() {
        while (firing.isNotEmpty()) firing.block { it.values.toList() }.forEach { it.join() }
    }

    private suspend fun onDue(agent: Agent, routine: Routine, slot: Long, now: Long) {
        if (routine.trigger is Trigger.Once) {
            harness.routines.update(agent, routine.id) { it.copy(enabled = false) }
            fire(agent, routine, waitUntil = now + MANUAL_WAIT)
            return
        }
        val latest = Schedule.latest(routine, slot, now)
        val next = Schedule.next(routine, now)
        harness.routines.update(agent, routine.id) { it.copy(nextRunAt = next) }
        if (now - latest <= Schedule.grace(routine, latest)) fire(agent, routine, waitUntil = next ?: (now + MANUAL_WAIT))
        else record(agent, routine, RunStatus.Skipped, "Missed the run at ${format(latest, routine)}: the server wasn't running")
    }

    private suspend fun fire(agent: Agent, routine: Routine, waitUntil: Long) {
        val key = "${agent.id}/${routine.id}"
        // A slot that comes due mid-run is dropped; the Routine still shows as running.
        if (firing[key]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(agent, routine, waitUntil)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record(agent, routine, RunStatus.Error, e.message ?: e.toString())
            }
        }
        firing[key] = job
        job.invokeOnCompletion { firing.removeExact(key, job) }
        job.start()
    }

    private suspend fun run(agent: Agent, routine: Routine, waitUntil: Long) {
        val ref = RoutineRef(routine.id, routine.name)
        val prompt = if (routine.heartbeat) heartbeatPrompt(routine.prompt)
            ?: return record(agent, routine, RunStatus.Skipped, "HEARTBEAT.md is empty") else routine.prompt
        val attached = routine.sessionId?.takeIf { harness.store.entries(agent, it).isNotEmpty() }
        if (routine.sessionId != null && attached == null) {
            println("Routine ${routine.id}: Session ${routine.sessionId} is gone; starting new Sessions instead")
            harness.routines.update(agent, routine.id) { it.copy(sessionId = null) }
        }
        if (attached != null) {
            // An attached Session may be busy with a user turn: wait for it, but not past the next slot.
            while (runs.isActive(attached) && clock() < waitUntil) withTimeoutOrNull(BUSY_POLL) { runs.join(attached) }
            if (runs.isActive(attached)) return record(agent, routine, RunStatus.Skipped, "Its Session was busy")
        }
        val startedAt = clock()
        val sessionId = attached ?: harness.newSession(agent, ref).id
        val before = harness.store.entries(agent, sessionId).size
        record(agent, routine, RunStatus.Running, null, startedAt, sessionId)
        var error: String? = null
        var kept: String? = sessionId
        var status = RunStatus.Ok
        // Cleans up while the Session is still reserved, so truncating can't take a turn that starts right after.
        val run = try { runs.start(agent, sessionId, prompt, routine = ref, onEvent = { if (it is AgentEvent.RunError) error = it.message }, onDone = { stopped ->
            val added = harness.store.entries(agent, sessionId).drop(before)
            val reply = added.filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text
            status = when {
                stopped -> RunStatus.Stopped
                error != null -> RunStatus.Error
                reply?.trim()?.startsWith(SILENT_REPLY) == true -> RunStatus.Silent
                else -> RunStatus.Ok
            }
            // A silent run's own Session stays in the Routine's history, except the Heartbeat's; one in an attached chat is removed from it.
            if (status == RunStatus.Silent && attached != null) {
                harness.log.discardSilentRun(agent, sessionId, before)
            } else if (status == RunStatus.Silent && routine.heartbeat) {
                kept = null
                harness.store.deleteSession(agent, sessionId)
            }
        }) {
            harness.runner(agent, sessionId, routine)
        } } catch (_: Runs.StoppedBeforeStart) { null }
        run?.job?.join()
        record(agent, routine, status, error, startedAt, kept)
        if (kept == null) harness.routines.update(agent, routine.id) { it.copy(lastSessionId = routine.lastSessionId) }
        if (status == RunStatus.Ok || status == RunStatus.Error) onRan(RoutineRan(agent.id, ref, status, kept, error))
    }

    private suspend fun record(agent: Agent, routine: Routine, status: RunStatus, error: String?, at: Long? = null, sessionId: String? = null) {
        harness.routines.update(agent, routine.id) {
            it.copy(lastStatus = status, lastError = error, lastRunAt = at ?: it.lastRunAt, lastSessionId = sessionId ?: it.lastSessionId)
        }
    }

    private fun heartbeatPrompt(checklist: String): String? = checklist.takeIf { it.isNotBlank() }?.let {
        "This is your Heartbeat, a periodic check-in. Work through the checklist below and act on anything that's due. " +
            "If nothing needs the user's attention, reply with only $SILENT_REPLY.\n\n## HEARTBEAT.md\n\n${it.trim()}"
    }

    private fun format(at: Long, routine: Routine) = formatTime(at, routine.timeZone)

    private companion object {
        const val MAX_SLEEP = 60 * 60_000L
        const val MANUAL_WAIT = 10 * 60_000L
        const val BUSY_POLL = 1_000L
    }
}
