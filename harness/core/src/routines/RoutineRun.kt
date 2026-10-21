package kinuvo.harness.core.routines

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.protocol.Risk
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.RunStatus
import kinuvo.protocol.SessionEntry
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * One unattended run of [routine]: what it knows going in, what its Agent reports and how that reaches the user.
 * [notify] tells the user about a report.
 */
class RoutineRun(
    val routine: Routine,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val notify: suspend (SessionEntry.Outcome) -> Unit = {},
) {
    val ref = RoutineRef(routine.id, routine.name)
    var outcome: SessionEntry.Outcome? = null
    /** Tool calls nobody approved in time. */
    val skipped = mutableListOf<String>()

    /** The run's first message: when it runs, how the last run ended, then [prompt]. */
    fun message(prompt: String) = listOfNotNull(
        "Run at ${formatTime(clock(), routine.timeZone)} (${routine.timeZone}), ${routine.scheduleText()}.",
        previous(),
        "\n$prompt\n\n$INSTRUCTIONS",
    ).joinToString("\n")

    /** Settles the outcome: what the Agent reported, else its [reply]; skipped calls always reach the user. */
    fun decide(reply: String) {
        val reported = outcome ?: SessionEntry.Outcome(true, reply.trim().ifEmpty { "Finished without a summary." })
        outcome = if (skipped.isEmpty()) reported
        else SessionEntry.Outcome(true, "${reported.summary}\nSkipped, no Approval: ${skipped.joinToString()}".trim())
    }

    private fun previous(): String? {
        val how = when (routine.lastStatus) {
            RunStatus.Ok -> "told the user: ${routine.lastReport}"
            RunStatus.Silent -> "found nothing new"
            RunStatus.Error -> "failed: ${routine.lastError}"
            RunStatus.Stopped -> "was stopped"
            else -> return null
        }
        return "Last run, ${formatTime(routine.lastRunAt ?: return null, routine.timeZone)}: $how."
    }

    private companion object {
        const val INSTRUCTIONS = "This run is unattended: finish the work with your tools and make reasonable assumptions. " +
            "Weigh what you find against the last run, so the user hears only what is new. Done when you have called routine_report once: " +
            "outcome report with a summary of one or two sentences for the user's phone when something is new or needs them, " +
            "including any call skipped for lack of an Approval, or outcome nothing_new when nothing does. Then close with a brief reply."
    }
}

/** How the Agent ends a Routine run: report to the user, or nothing new. */
class RoutineReportTool(private val run: RoutineRun) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = ToolNames.ROUTINE_REPORT,
        description = "End this Routine run: report when something is new or needs the user, whose summary is their notification, " +
            "or nothing_new when nothing does, which sends none. Call it once, after the work.",
        requiredParameters = listOf(ToolParameterDescriptor("outcome", "report | nothing_new", ToolParameterType.Enum(arrayOf("report", "nothing_new")))),
        optionalParameters = listOf(ToolParameterDescriptor("summary", "With report: one or two sentences, what's new first", ToolParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val report = when (val outcome = args.requireString("outcome")) {
            "report" -> true
            "nothing_new" -> false
            else -> error("outcome must be report or nothing_new, not '$outcome'")
        }
        val summary = args.string("summary")?.trim().orEmpty()
        require(!report || summary.isNotEmpty()) { "Give a summary to report" }
        run.outcome = SessionEntry.Outcome(report, if (report) summary else "")
        return "Recorded."
    }
}
