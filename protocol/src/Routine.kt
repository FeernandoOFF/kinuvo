package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** When a Routine fires. Times are epoch milliseconds; [Cron] uses the Routine's time zone. */
@Serializable
sealed interface Trigger {
    @Serializable @SerialName("once")
    data class Once(val at: Long) : Trigger

    @Serializable @SerialName("every")
    data class Every(val minutes: Long) : Trigger

    /** Five fields: minute hour day-of-month month day-of-week. */
    @Serializable @SerialName("cron")
    data class Cron(val expr: String) : Trigger
}

/** Daily window, as `HH:mm` in the Routine's time zone, outside which a recurring Routine doesn't fire. */
@Serializable
data class ActiveHours(val start: String, val end: String)

/** How a run handles tool calls that need an Approval: as the Agent does, run them all, or deny them. */
@Serializable
enum class RoutineApprovals { Inherit, Auto, Deny }

@Serializable
enum class RunStatus { Ok, Error, Skipped, Silent, Stopped, Running }

/**
 * A saved prompt an Agent runs on a [Trigger]. Each run starts a new Session, or continues [sessionId] when set.
 * The Heartbeat is the Routine with [heartbeat] set; its prompt is the Agent's HEARTBEAT.md.
 */
@Serializable
data class Routine(
    val id: String,
    val agentId: String,
    val name: String,
    val prompt: String,
    val trigger: Trigger,
    val timeZone: String,
    val enabled: Boolean = true,
    val sessionId: String? = null,
    val approvals: RoutineApprovals = RoutineApprovals.Inherit,
    val activeHours: ActiveHours? = null,
    val heartbeat: Boolean = false,
    val createdAt: Long = 0,
    val nextRunAt: Long? = null,
    /** Set by "run now"; the scheduler clears it when it starts the run. */
    val runRequested: Boolean = false,
    val lastRunAt: Long? = null,
    val lastStatus: RunStatus? = null,
    val lastError: String? = null,
    val lastSessionId: String? = null,
    /** What the last run told the user, for the next run to build on; null when it found nothing new. */
    val lastReport: String? = null,
)

/** Names the Routine that started a Session or sent a message. */
@Serializable
data class RoutineRef(val id: String, val name: String)

@Serializable
data class RoutineParams(val agentId: String, val routineId: String)

/**
 * A Routine as the user writes it; on update, null fields stay as they are. The schedule is one of [at] (local
 * date-time, e.g. `2026-05-01T09:00`), [every] (`30m`, `2h`, `1d`) or [cron]. [activeHours] is `HH:mm-HH:mm` or `none`;
 * [session] is `new` (a fresh Session per run) or the ID of a Session every run continues.
 */
@Serializable
data class RoutineSpec(
    val name: String? = null,
    val prompt: String? = null,
    val at: String? = null,
    val every: String? = null,
    val cron: String? = null,
    val timeZone: String? = null,
    val activeHours: String? = null,
    val session: String? = null,
    val approvals: RoutineApprovals? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class CreateRoutineParams(val agentId: String, val spec: RoutineSpec)

@Serializable
data class UpdateRoutineParams(val agentId: String, val routineId: String, val spec: RoutineSpec)

/** All of an Agent's Routines after any of them changed. */
@Serializable
data class RoutinesChanged(val agentId: String, val routines: List<Routine>)

/** A run finished with something to show ([RunStatus.Ok] with its [summary], or [RunStatus.Error]). */
@Serializable
data class RoutineRan(val agentId: String, val routine: RoutineRef, val status: RunStatus, val sessionId: String?, val error: String? = null, val summary: String? = null)
