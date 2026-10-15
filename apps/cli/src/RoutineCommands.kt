package kinuvo.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import kinuvo.protocol.*

internal class RoutineCommands : Group(
    "routines", "Manage Routines, prompts an Agent runs on a schedule (Hermes: cron).",
    ListRoutines(), ShowRoutine(), CreateRoutine(), EditRoutine(),
    Toggle("pause", "Turn a Routine off without deleting it.", false), Toggle("resume", "Turn a paused Routine back on.", true),
    RunRoutine(), RemoveRoutine(), RoutineRuns(),
)

private class ScheduleOptions : OptionGroup("Schedule") {
    val at by option("--at", help = "Run once at this local date-time, e.g. 2026-05-01T09:00")
    val every by option("--every", help = "Run every interval, e.g. 30m, 2h, 1d")
    val cron by option("--cron", help = "Cron expression: minute hour day month weekday, e.g. '0 9 * * MON-FRI'")
    val timeZone by option("--tz", help = "IANA time zone (default: yours)")
    val activeHours by option("--active-hours", help = "Only fire between these local times, e.g. 08:00-22:00; none to clear")
    val session by option("--session", help = "new: a fresh Session per run; or a Session ID every run continues")
    val approvals by option("--approvals", help = "Tool calls needing an Approval: inherit (wait for you), auto or deny")
        .enum<RoutineApprovals> { it.name.lowercase() }

    fun spec() = RoutineSpec(at = at, every = every, cron = cron, timeZone = timeZone, activeHours = activeHours, session = session, approvals = approvals)
}

private class ListRoutines : ServerCommand("list", "List the Agent's Routines.") {
    override suspend fun run() {
        val routines = session.call { client.call(Api.Routines, AgentRef(agentId())) }
        echo(table(listOf("ID", "NAME", "SCHEDULE", "NEXT RUN", "LAST RUN"), routines.map { r ->
            listOf(
                r.id, r.name, r.trigger.describe(),
                if (!r.enabled) "paused" else r.nextRunAt?.let { time(it, r.timeZone) } ?: "-",
                r.lastRunAt?.let { "${time(it, r.timeZone)} ${r.lastStatus?.name?.lowercase().orEmpty()}" } ?: "-",
            )
        }))
    }
}

private class ShowRoutine : ServerCommand("show", "Show a Routine.") {
    private val id by argument("ID")

    override suspend fun run() {
        val r = session.call { client.call(Api.Routines, AgentRef(agentId())).firstOrNull { it.id == id } ?: error("No routine '$id'") }
        echo(describe(r))
    }
}

private class CreateRoutine : ServerCommand("create", "Create a Routine; give one of --at, --every or --cron.") {
    private val name by argument("NAME")
    private val prompt by argument("PROMPT", help = "What to do on each run; - reads stdin")
    private val schedule by ScheduleOptions()
    private val paused by option("--paused", help = "Create it turned off").flag()

    override suspend fun run() {
        val spec = schedule.spec().copy(name = name, prompt = textOrStdin(prompt), enabled = !paused)
        val r = session.call { client.call(Api.CreateRoutine, CreateRoutineParams(agentId(), spec)) }
        echo("Created routine ${r.id}.\n${describe(r)}")
    }
}

private class EditRoutine : ServerCommand("edit", "Change a Routine; options not given stay as they are.") {
    private val id by argument("ID")
    private val name by option("--name")
    private val prompt by option("--prompt", help = "- reads stdin")
    private val schedule by ScheduleOptions()

    override suspend fun run() {
        val spec = schedule.spec().copy(name = name, prompt = prompt?.let(::textOrStdin))
        val r = session.call { client.call(Api.UpdateRoutine, UpdateRoutineParams(agentId(), id, spec)) }
        echo(describe(r))
    }
}

private class Toggle(name: String, about: String, private val enabled: Boolean) : ServerCommand(name, about) {
    private val id by argument("ID")

    override suspend fun run() {
        val r = session.call { client.call(Api.UpdateRoutine, UpdateRoutineParams(agentId(), id, RoutineSpec(enabled = enabled))) }
        echo("${r.name} is ${if (r.enabled) "on" else "paused"}.")
    }
}

private class RunRoutine : ServerCommand("run", "Run a Routine now; its schedule doesn't change.") {
    private val id by argument("ID")

    override suspend fun run() {
        session.call { client.call(Api.RunRoutine, RoutineParams(agentId(), id)) }
        echo("Started; the run shows up in its own Session.")
    }
}

private class RemoveRoutine : ServerCommand("remove", "Delete a Routine (the Heartbeat is turned off instead).") {
    private val id by argument("ID")

    override suspend fun run() {
        session.call { client.call(Api.RemoveRoutine, RoutineParams(agentId(), id)) }
        echo("Removed $id.")
    }
}

private class RoutineRuns : ServerCommand("runs", "List the Sessions a Routine ran in, newest first.") {
    private val id by argument("ID")

    override suspend fun run() {
        val page = session.call { client.call(Api.Sessions, SessionsParams(agentId(), limit = 20, routineId = id)) }
        echo(table(listOf("SESSION", "STATUS"), page.sessions.map { listOf(it.id, it.status.name.lowercase()) }))
    }
}

private fun describe(r: Routine): String = table(listOf("ID", r.id), listOfNotNull(
    listOf("name", r.name),
    listOf("schedule", r.trigger.describe() + ((r.trigger as? Trigger.Once)?.let { " at ${time(it.at, r.timeZone)}" } ?: "")),
    listOf("time zone", r.timeZone),
    listOf("active hours", r.activeHours?.let { "${it.start}-${it.end}" } ?: "any time"),
    listOf("state", if (r.enabled) "on" else "paused"),
    listOf("next run", r.nextRunAt?.takeIf { r.enabled }?.let { time(it, r.timeZone) } ?: "-"),
    listOf("session", r.sessionId ?: "new each run"),
    listOf("approvals", r.approvals.name.lowercase()),
    r.lastRunAt?.let { listOf("last run", "${time(it, r.timeZone)} ${r.lastStatus?.name?.lowercase().orEmpty()}") },
    r.lastError?.let { listOf("last error", it) },
)) + "\n\n" + r.prompt.ifBlank { "(empty prompt)" }.trim()
