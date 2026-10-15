package kinuvo.harness.core.routines

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.AgentDocs
import kinuvo.harness.core.update
import kinuvo.protocol.ActiveHours
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineSpec
import kinuvo.protocol.Trigger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Clock

/** Each Agent's stored Routines, Heartbeat included (its prompt lives in [AgentDoc.Heartbeat], not here). */
interface RoutineRepository {
    suspend fun get(agentId: String): List<Routine>
    /** Read-modify-write in one step; returns the new list. */
    suspend fun update(agentId: String, transform: (List<Routine>) -> List<Routine>): List<Routine>
}

/**
 * Each Agent's Routines. Every Agent has a Heartbeat, off until enabled, whose prompt is its [AgentDoc.Heartbeat]
 * checklist. Changes go to every [onChange] listener with the Agent's Routines as they now are.
 */
@OptIn(ExperimentalAtomicApi::class)
class Routines(
    private val repository: RoutineRepository,
    private val docs: AgentDocs,
    private val defaultZone: () -> TimeZone = TimeZone::currentSystemDefault,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val listeners = AtomicReference(emptyList<(Agent, List<Routine>) -> Unit>())

    /** [listener] must not block: it's called while holding the lock, so updates arrive in order. */
    fun onChange(listener: (Agent, List<Routine>) -> Unit) = listeners.update { it + listener }

    suspend fun list(agent: Agent): List<Routine> = lock.withLock { read(agent) }

    suspend fun get(agent: Agent, id: String): Routine? = list(agent).firstOrNull { it.id == id }

    suspend fun create(
        agent: Agent,
        name: String,
        prompt: String,
        trigger: Trigger,
        timeZone: String? = null,
        configure: (Routine) -> Routine = { it },
    ): Routine {
        val now = clock()
        val routine = configure(
            Routine(
                id = Random.nextInt(0x10000000, Int.MAX_VALUE).toString(16),
                agentId = agent.id,
                name = name,
                prompt = prompt,
                trigger = trigger,
                timeZone = timeZone ?: defaultZone().id,
                createdAt = now,
            )
        )
        validate(routine)
        require(trigger !is Trigger.Once || trigger.at > now) { "The time is in the past" }
        val saved = routine.rescheduled(now)
        lock.withLock { mutate(agent) { it + saved } }
        return saved
    }

    /** A Routine from what the user wrote; [RoutineSpec.name], [RoutineSpec.prompt] and a schedule are required. */
    suspend fun create(agent: Agent, spec: RoutineSpec): Routine {
        val timeZone = spec.timeZone ?: defaultZone().id
        val trigger = spec.trigger(zone(timeZone)) ?: error("Give one of at, every or cron")
        return create(agent, spec.name ?: error("name is required"), spec.prompt.orEmpty(), trigger, timeZone) { it.with(spec) }
    }

    /** Applies [transform] and reschedules when the schedule changed; null when there's no such Routine. */
    suspend fun update(agent: Agent, id: String, transform: (Routine) -> Routine): Routine? = lock.withLock {
        var saved: Routine? = null
        mutate(agent) { all ->
            val old = all.firstOrNull { it.id == id } ?: return@mutate all
            val new = transform(old).copy(id = old.id, agentId = old.agentId, heartbeat = old.heartbeat)
            validate(new)
            val scheduleChanged = new.trigger != old.trigger || new.enabled != old.enabled ||
                new.timeZone != old.timeZone || new.activeHours != old.activeHours
            val updated = if (scheduleChanged) new.rescheduled(clock()) else new
            saved = updated
            all.map { if (it.id == id) updated else it }
        }
        saved
    }

    /** Deletes a Routine; the Heartbeat can only be turned off. */
    suspend fun remove(agent: Agent, id: String): Boolean {
        if (id == HEARTBEAT_ID) return update(agent, id) { it.copy(enabled = false) } != null
        return lock.withLock {
            var removed = false
            mutate(agent) { all -> all.filterNot { it.id == id }.also { removed = it.size < all.size } }
            removed
        }
    }

    private fun Routine.rescheduled(now: Long) = copy(nextRunAt = if (enabled) Schedule.next(this, now) else null)

    private fun validate(r: Routine) {
        require(r.name.isNotBlank()) { "name is required" }
        require(r.heartbeat || r.prompt.isNotBlank()) { "prompt is required" }
        val zone = zone(r.timeZone)
        r.activeHours?.let { h -> runCatching { h.contains(0, zone) }.getOrElse { throw IllegalArgumentException("active hours must be HH:mm-HH:mm") } }
        Schedule.validate(r.trigger)
    }

    private suspend fun read(agent: Agent): List<Routine> = withHeartbeat(agent, repository.get(agent.id), docs.read(agent.id, AgentDoc.Heartbeat))

    /** Applies [transform] to the stored Routines inside the repository's atomic update; [transform] may fail to abort. */
    private suspend fun mutate(agent: Agent, transform: (List<Routine>) -> List<Routine>) {
        val prompt = docs.read(agent.id, AgentDoc.Heartbeat)
        var routines = emptyList<Routine>()
        repository.update(agent.id) { stored ->
            routines = transform(withHeartbeat(agent, stored, prompt))
            routines.map { if (it.heartbeat) it.copy(prompt = "") else it }
        }
        routines.firstOrNull { it.heartbeat }?.let { if (it.prompt != prompt) docs.write(agent.id, AgentDoc.Heartbeat, it.prompt) }
        listeners.load().forEach { it(agent, routines) }
    }

    private fun withHeartbeat(agent: Agent, stored: List<Routine>, prompt: String): List<Routine> =
        listOf((stored.firstOrNull { it.heartbeat } ?: defaultHeartbeat(agent)).copy(prompt = prompt)) + stored.filterNot { it.heartbeat }

    private fun defaultHeartbeat(agent: Agent) = Routine(
        id = HEARTBEAT_ID,
        agentId = agent.id,
        name = "Heartbeat",
        prompt = "",
        trigger = Trigger.Every(30),
        timeZone = defaultZone().id,
        enabled = false,
        activeHours = ActiveHours("08:00", "22:00"),
        heartbeat = true,
        createdAt = 0,
    )

    companion object {
        const val HEARTBEAT_ID = "heartbeat"
    }
}
