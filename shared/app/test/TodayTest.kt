package kinuvo.app

import kinuvo.app.home.*
import kinuvo.protocol.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

class TodayTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z").toEpochMilliseconds()
    private val utc = TimeZone.UTC
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun session(id: String, status: SessionStatus = SessionStatus.Idle, agent: String = "a") = SessionInfo(id, agent, "Session $id", status)
    private fun routine(id: String, agent: String = "a", enabled: Boolean = true, next: Long? = null, lastAt: Long? = null, status: RunStatus? = null, last: String? = null) =
        Routine(id, agent, "Routine $id", "prompt", Trigger.Every(60), "UTC", enabled, nextRunAt = next, lastRunAt = lastAt, lastStatus = status, lastSessionId = last)
    private fun task(id: String, status: TaskStatus = TaskStatus.Todo, due: String? = null, sessions: List<String> = emptyList()) =
        TaskInfo(id, "list", "List", "Task $id", due = due, status = status, sessionIds = sessions)
    private fun today(
        sessions: Map<String, List<SessionInfo>> = emptyMap(),
        routines: Map<String, List<Routine>> = emptyMap(),
        tasks: List<TaskInfo>? = emptyList(),
        finished: Set<String> = emptySet(),
        zone: TimeZone = utc,
    ) = Today.of(now, zone, sessions, routines, tasks, finished)

    @Test fun dateIsTheDayInTheGivenTimeZone() {
        assertEquals(LocalDate(2026, 10, 27), today().date)
        assertEquals(LocalDate(2026, 10, 28), today(zone = TimeZone.of("Pacific/Auckland")).date)
        assertEquals(LocalDate(2026, 10, 27), today(zone = TimeZone.of("America/Los_Angeles")).date)
    }

    @Test fun needsYouListsWaitingSessionsAndOnlyTasksNoWaitingSessionCovers() {
        val result = today(
            sessions = mapOf("a" to listOf(session("s1", SessionStatus.Waiting), session("s2")), "b" to listOf(session("s1", SessionStatus.Waiting, "b"), session("s9", SessionStatus.Waiting, "b"))),
            tasks = listOf(
                task("covered", TaskStatus.Waiting, sessions = listOf("s1")),
                task("alone", TaskStatus.Waiting),
                task("idleSession", TaskStatus.Waiting, sessions = listOf("s2")),
                task("todo"),
            ),
        )
        assertEquals(listOf("s9", "s1"), result.needsYou.filterIsInstance<Attention.Waiting>().map { it.session.id })
        assertEquals(listOf("alone", "idleSession"), result.needsYou.filterIsInstance<Attention.TaskWaiting>().map { it.task.id })
        assertEquals(4, result.needsYou.size)
        assertIs<Attention.Waiting>(result.needsYou.first())
    }

    @Test fun runningListsRunningSessionsAndRoutinesThatOnlyTheRoutineSaysRun() {
        val result = today(
            sessions = mapOf("a" to listOf(session("s1", SessionStatus.Running), session("s2"))),
            routines = mapOf("a" to listOf(
                routine("listed", status = RunStatus.Running, last = "s1"),
                routine("only", status = RunStatus.Running, last = "s7"),
                routine("noSession", status = RunStatus.Running),
                routine("done", status = RunStatus.Ok, last = "s2"),
            )),
        )
        assertEquals(listOf("s1", "s7"), result.running.map { it.sessionId })
        assertEquals("Session s1", result.running[0].title)
        assertNotNull(result.running[0].session)
        assertNull(result.running[0].routine)
        assertEquals("Routine only", result.running[1].title)
        assertEquals("only", result.running[1].routine?.id)
        assertNull(result.running[1].session)
    }

    @Test fun nextSortsEnabledUpcomingRunsAndKeepsOnesJustMissed() {
        val result = today(routines = mapOf("a" to listOf(
            routine("late", next = now + hour),
            routine("soon", next = now + 30 * minute),
            routine("disabled", enabled = false, next = now + 10 * minute),
            routine("past", next = now - 5 * minute),
            routine("justNow", next = now - 30_000),
            routine("none"),
        ), "b" to listOf(routine("tomorrow", "b", next = now + 24 * hour))))
        assertEquals(listOf("justNow", "soon", "late", "tomorrow"), result.next.map { it.routine.id })
        assertEquals(LocalDate(2026, 10, 27), result.next[1].day)
        assertEquals(LocalDate(2026, 10, 28), result.next.last().day)
        assertEquals(now + 30 * minute, result.next[1].at)
    }

    @Test fun nextDaysFollowTheTimeZoneAndAreCappedAtEight() {
        val routines = (1..10).map { routine("r$it", next = now + it * hour) }
        val result = today(routines = mapOf("a" to routines), zone = TimeZone.of("Pacific/Auckland"))
        assertEquals((1..8).map { "r$it" }, result.next.map { it.routine.id })
        assertEquals(LocalDate(2026, 10, 28), result.next.first().day)
    }

    @Test fun dueCoversOverdueThroughTheNextSevenDaysWithoutDoneOrUndatedTasks() {
        val result = today(tasks = listOf(
            task("edge", due = "2026-11-03"),
            task("beyond", due = "2026-11-04"),
            task("today", due = "2026-10-27"),
            task("overdue", due = "2026-10-20"),
            task("done", TaskStatus.Done, due = "2026-10-20"),
            task("none"),
            task("junk", due = "soon"),
            task("timed", due = "2026-10-28T09:00:00Z"),
        ))
        val due = assertNotNull(result.due)
        assertEquals(listOf("overdue", "today", "timed", "edge"), due.map { it.task.id })
        assertEquals(listOf(true, false, false, false), due.map { it.overdue })
        assertEquals(LocalDate(2026, 10, 28), due[2].date)
    }

    @Test fun dueIsNullUntilTasksLoadAndEmptyWithoutAny() {
        assertNull(today(tasks = null).due)
        assertEquals(emptyList(), today(tasks = emptyList()).due)
        assertEquals(emptyList(), today(tasks = null).needsYou)
    }

    @Test fun dueUsesTheLocalDay() {
        val tasks = listOf(task("t", due = "2026-10-27"))
        assertFalse(today(tasks = tasks).due!!.single().overdue)
        assertTrue(today(tasks = tasks, zone = TimeZone.of("Pacific/Auckland")).due!!.single().overdue)
    }

    @Test fun resultsKeepRecentFinishedRoutineRunsAndFinishedSessions() {
        val since = now - 48 * hour
        val result = today(
            sessions = mapOf("a" to listOf(session("done1"), session("done2"), session("never"))),
            routines = mapOf("a" to listOf(
                routine("ok", lastAt = now - hour, status = RunStatus.Ok, last = "done2"),
                routine("error", lastAt = now - 3 * hour, status = RunStatus.Error, last = "x"),
                routine("stopped", lastAt = since, status = RunStatus.Stopped, last = "y"),
                routine("old", lastAt = since - 1, status = RunStatus.Ok, last = "z"),
                routine("skipped", lastAt = now - hour, status = RunStatus.Skipped, last = "z"),
                routine("silent", lastAt = now - hour, status = RunStatus.Silent, last = "z"),
                routine("running", lastAt = now - hour, status = RunStatus.Running, last = "z"),
                routine("noSession", lastAt = now - hour, status = RunStatus.Ok),
                routine("never", status = RunStatus.Ok, last = "z"),
            )),
            finished = setOf("done1", "done2"),
        )
        assertEquals(listOf("done1"), result.results.filterIsInstance<Outcome.Finished>().map { it.session.id })
        assertEquals(listOf("ok", "error", "stopped"), result.results.filterIsInstance<Outcome.RoutineRun>().map { it.routine.id })
        assertIs<Outcome.Finished>(result.results.first())
        assertNull(result.results.first().at)
        assertEquals(now - hour, result.results[1].at)
        assertEquals(4, result.results.size)
    }

    @Test fun resultsAreCappedAtSix() {
        val routines = (1..9).map { routine("r$it", lastAt = now - it * minute, status = RunStatus.Ok, last = "s$it") }
        val result = today(routines = mapOf("a" to routines))
        assertEquals((1..6).map { "r$it" }, result.results.map { (it as Outcome.RoutineRun).routine.id })
    }
}
