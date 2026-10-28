package kinuvo.app.home

import kinuvo.protocol.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A Session or Task waiting on the user: an Approval, a form or a Task's question. */
sealed interface Attention {
    data class Waiting(val session: SessionInfo) : Attention
    data class TaskWaiting(val task: TaskInfo) : Attention
}

/** A Session running now: one the user started, a Task's run or a Routine's ([routine] when only the Routine says so). */
data class Running(val agentId: String, val sessionId: String, val title: String, val session: SessionInfo? = null, val routine: Routine? = null)

/** A Routine's next run, [at] epoch ms. */
data class Upcoming(val routine: Routine, val at: Long, val day: LocalDate)

/** What came out lately: a Routine's last run, or a Session that finished while the user was elsewhere. */
sealed interface Outcome {
    val at: Long?
    data class RoutineRun(val routine: Routine) : Outcome { override val at get() = routine.lastRunAt }
    data class Finished(val session: SessionInfo) : Outcome { override val at: Long? get() = null }
}

/** A Task due by the end of the week, [overdue] when its date has passed. */
data class Due(val task: TaskInfo, val date: LocalDate, val overdue: Boolean)

/**
 * The Server's Today, across every Agent: what [needsYou], what's [running], the [next] Routine runs, Tasks [due] within
 * [DueDays] and the latest [results]. [tasks] is null while the Server has no Tasks or they haven't loaded.
 */
data class Today(
    val date: LocalDate,
    val needsYou: List<Attention> = emptyList(),
    val running: List<Running> = emptyList(),
    val next: List<Upcoming> = emptyList(),
    val due: List<Due>? = null,
    val results: List<Outcome> = emptyList(),
) {
    companion object {
        const val DueDays = 7
        const val ResultHours = 48L
        const val NextRuns = 8
        const val Results = 6

        fun of(
            now: Long,
            zone: TimeZone,
            sessions: Map<String, List<SessionInfo>>,
            routines: Map<String, List<Routine>>,
            tasks: List<TaskInfo>?,
            finished: Set<String> = emptySet(),
        ): Today {
            val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
            val all = sessions.values.flatten().distinctBy { it.id }
            val waiting = all.filter { it.status == SessionStatus.Waiting }.sortedByDescending { it.id }
            val waitingIds = waiting.map { it.id }.toSet()
            val waitingTasks = tasks.orEmpty().filter { it.status == TaskStatus.Waiting && it.sessionIds.none(waitingIds::contains) }
            val running = all.filter { it.status == SessionStatus.Running }.sortedByDescending { it.id }
                .map { Running(it.agentId, it.id, it.title, session = it) }
            val routineRuns = routines.values.flatten()
                .filter { it.lastStatus == RunStatus.Running && it.lastSessionId != null && running.none { r -> r.sessionId == it.lastSessionId } }
                .map { Running(it.agentId, it.lastSessionId!!, it.name, routine = it) }
            val next = routines.values.flatten()
                .mapNotNull { r -> r.nextRunAt?.takeIf { r.enabled && it >= now - 60_000 }?.let { Upcoming(r, it, Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date) } }
                .sortedBy { it.at }.take(NextRuns)
            val end = date.plus(DatePeriod(days = DueDays))
            val due = tasks?.mapNotNull { t ->
                val day = t.due?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                Due(t, day, day < date).takeIf { t.status != TaskStatus.Done && day <= end }
            }?.sortedBy { it.date }
            val since = now - ResultHours * 3_600_000
            val reported = routines.values.flatten()
                .filter { (it.lastRunAt ?: 0) >= since && it.lastSessionId != null && it.lastStatus in setOf(RunStatus.Ok, RunStatus.Error, RunStatus.Stopped) }
                .sortedByDescending { it.lastRunAt }.map { Outcome.RoutineRun(it) }
            val finishedSessions = all.filter { it.id in finished && reported.none { r -> r.routine.lastSessionId == it.id } }.map { Outcome.Finished(it) }
            return Today(
                date,
                waiting.map { Attention.Waiting(it) } + waitingTasks.map { Attention.TaskWaiting(it) },
                running + routineRuns,
                next,
                due,
                (finishedSessions + reported).take(Results),
            )
        }
    }
}
