package kinuvo.harness.core.routines

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.Agent
import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.Action
import kinuvo.harness.core.tools.Assessment
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineApprovals
import kinuvo.protocol.RoutineSpec
import kinuvo.protocol.Trigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Manages the Agent's Routines. [sessionId] is the Session the call comes from, the default for `session=attach`.
 * Inside a run of [routine] only `list` and removing that Routine are allowed, so runs can't schedule more runs.
 */
class ScheduleTool(
    private val agent: Agent,
    private val routines: Routines,
    private val defaultZone: () -> TimeZone,
    private val sessionId: String? = null,
    private val routine: Routine? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : Tool {
    override val risk = Risk.High

    override fun assess(args: JsonObject): Assessment {
        val risk = when ((args["action"] as? JsonPrimitive)?.content) {
            "list", "pause", "remove" -> Risk.Low
            "create" -> Risk.Medium
            else -> Risk.High
        }
        return Assessment(listOf(Action(risk, listOf(descriptor.name), when (risk) {
            Risk.Low -> null
            Risk.Medium -> "Creates a Routine that runs with Relaxed risk tolerance"
            Risk.High -> "Changes or starts a Routine that may run with Auto approvals"
        }, trustable = risk != Risk.High)))
    }

    override val descriptor = ToolDescriptor(
        name = ToolNames.SCHEDULE,
        description = "Manage your Routines: prompts you run on a schedule, each in a fresh Session by default. " +
            "Use them for reminders, recurring reports and checks. A run sees only your instructions, Memory and the prompt, " +
            "so write self-contained prompts. A run that needs the user (an Approval or ask_user) notifies them and waits. A run that ends its reply with only [SILENT] is kept only in the routine's history, without notifying the user. " +
            "The routine with id 'heartbeat' is your Heartbeat: its prompt is your HEARTBEAT.md checklist. " +
            "Now: ${now()}. The user's time zone: ${defaultZone().id}." +
            (routine?.let { " You are running as the routine '${it.name}' (id ${it.id}): you can only list routines or remove this one." } ?: ""),
        requiredParameters = listOf(
            ToolParameterDescriptor("action", "create | list | update | pause | resume | run | remove", ToolParameterType.Enum(ACTIONS)),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("id", "Routine id, for every action but create and list", ToolParameterType.String),
            ToolParameterDescriptor("name", "Short name, e.g. 'Morning brief'", ToolParameterType.String),
            ToolParameterDescriptor("prompt", "What to do on each run, self-contained", ToolParameterType.String),
            ToolParameterDescriptor("at", "One-shot: local date-time, e.g. 2026-05-01T09:00", ToolParameterType.String),
            ToolParameterDescriptor("every", "Recurring interval, e.g. 30m, 2h, 1d", ToolParameterType.String),
            ToolParameterDescriptor("cron", "Recurring cron expression: minute hour day month weekday, e.g. '0 9 * * MON-FRI'", ToolParameterType.String),
            ToolParameterDescriptor("time_zone", "IANA time zone for at/cron/active_hours; defaults to the user's", ToolParameterType.String),
            ToolParameterDescriptor("active_hours", "Only fire between these local times, e.g. 08:00-22:00; none to clear", ToolParameterType.String),
            ToolParameterDescriptor("session", "new: a fresh Session per run (the default); attach: continue one Session every run", ToolParameterType.String),
            ToolParameterDescriptor("session_id", "Session to continue with session=attach; defaults to the current one", ToolParameterType.String),
            ToolParameterDescriptor(
                "approvals", "Tool calls that need an Approval during a run: inherit (notify and wait for the user) or deny (also no ask_user). " +
                    "Only the user can set auto, in Settings", ToolParameterType.String,
            ),
        ),
    )

    // Some providers send every optional parameter, blank when unused.
    override suspend fun execute(args: JsonObject): String =
        handle(JsonObject(args.filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false }))

    private suspend fun handle(args: JsonObject): String {
        val action = args.requireString("action")
        if (routine != null && action != "list" && !(action == "remove" && (args.string("id") ?: routine.id) == routine.id)) {
            error("Routines can't change routines while running; you can only list them or remove this one")
        }
        return when (action) {
            "list" -> routines.list(agent).joinToString("\n") { it.describe() }
            "create" -> routines.create(agent, spec(args).copy(name = args.requireString("name"), prompt = args.requireString("prompt"))).let { "Created.\n${it.describe()}" }
            "update" -> spec(args).let { spec -> updated(args) { it.with(spec) } }
            "pause" -> updated(args) { it.copy(enabled = false) }
            "resume" -> updated(args) { it.copy(enabled = true) }
            "run" -> updated(args) { it.copy(runRequested = true) }.let { "Run started; it shows up in its own Session.\n$it" }
            "remove" -> {
                val id = args.string("id") ?: routine?.id ?: error("Missing 'id'")
                check(routines.remove(agent, id)) { "No routine '$id'" }
                if (id == Routines.HEARTBEAT_ID) "The Heartbeat can't be deleted; turned it off." else "Removed."
            }
            else -> error("Unknown action '$action'")
        }
    }

    private suspend fun updated(args: JsonObject, transform: (Routine) -> Routine): String {
        val id = args.requireString("id")
        return routines.update(agent, id, transform)?.let { "Updated.\n${it.describe()}" } ?: error("No routine '$id'")
    }

    private fun spec(args: JsonObject) = RoutineSpec(
        name = args.string("name"),
        prompt = args.string("prompt"),
        at = args.string("at"),
        every = args.string("every"),
        cron = args.string("cron"),
        timeZone = args.string("time_zone"),
        activeHours = args.string("active_hours"),
        approvals = when (args.string("approvals")) {
            null -> null
            "inherit" -> RoutineApprovals.Inherit
            "deny" -> RoutineApprovals.Deny
            else -> error("approvals must be inherit or deny")
        },
        session = when (args.string("session")) {
            null -> null
            "new" -> NEW_SESSIONS
            "attach" -> args.string("session_id") ?: sessionId ?: error("No current Session to attach to; give session_id")
            else -> error("session must be new or attach")
        },
    )

    private fun now() = NOW.format(Instant.fromEpochMilliseconds(clock()).toLocalDateTime(defaultZone()))

    private fun Routine.describe(): String {
        fun time(t: Long) = formatTime(t, timeZone)
        val schedule = when (val t = trigger) {
            is Trigger.Once -> "once at ${time(t.at)}"
            is Trigger.Every -> "every ${t.minutes}m"
            is Trigger.Cron -> "cron '${t.expr}'"
        }
        return buildString {
            append("- $id \"$name\": $schedule ($timeZone)")
            activeHours?.let { append(", active ${it.start}-${it.end}") }
            append(if (enabled) nextRunAt?.let { ", next ${time(it)}" } ?: ", done" else ", paused")
            sessionId?.let { append(", continues Session $it") }
            if (approvals != RoutineApprovals.Inherit) append(", approvals ${approvals.name.lowercase()}")
            lastStatus?.let { append(", last run ${it.name.lowercase()}") }
            lastError?.let { append(" ($it)") }
            append("\n  prompt: ${prompt.ifBlank { "(empty)" }.replace("\n", "\n  ")}")
        }
    }

    private companion object {
        val NOW = LocalDateTime.Format {
            date(LocalDate.Formats.ISO); char('T'); hour(); char(':'); minute(); char(' '); dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
        }
        val ACTIONS = arrayOf("create", "list", "update", "pause", "resume", "run", "remove")
    }
}

private val TIME = LocalDateTime.Format { date(LocalDate.Formats.ISO); char(' '); hour(); char(':'); minute() }

/** [at] as `yyyy-MM-dd HH:mm` in [timeZone]. */
fun formatTime(at: Long, timeZone: String): String = TIME.format(Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.of(timeZone)))
