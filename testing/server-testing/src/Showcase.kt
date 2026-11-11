package kinuvo.testing.server

import kinuvo.harness.Agent
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.agent.ConfigScope
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.board.TaskStatus
import kinuvo.protocol.routine.Trigger
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val RELEASE_NOTES = """
    ## Release notes draft

    Here's the **2.0** summary, grouped by area:

    1. Sync is now *incremental*
    2. Offline edits merge without prompts
    3. New `kinuvo pair` command

    | Area | Change | Risk |
    | --- | --- | --- |
    | Sync | Incremental uploads | Medium |
    | CLI | `pair` and `unpair` | Low |
    | Mobile | Voice chat | Low |

    Run the migration with:

    ```sh
    kinuvo migrate --from 1.9 --dry-run
    kinuvo migrate --from 1.9
    ```

    - Inline code like `SessionStatus.Waiting` stays readable
    - Nested items:
      - keep their indent
      - and wrap long lines without breaking the layout of the list
""".trimIndent()

private val MIGRATION_GUIDE = """
    ### Migration guide

    Upgrading from 1.9 keeps every Session, Routine and Task. The first launch rebuilds the search index, which can take a few minutes on large Servers; the app stays usable while it runs.

    Paired Devices reconnect on their own. If a Device was offline for more than thirty days it asks to pair again, because its token expired. Nothing else changes for it.

    Agents keep their settings. Two settings moved: risk tolerance now lives under Rules, and the reasoning effort moved next to the model. Old values carry over.

    Routines that ran on a fixed interval keep their schedule. Cron Routines are re-evaluated in the Server's time zone, so check any that ran at midnight.

    Finally, the CLI gained `kinuvo pair` and `kinuvo unpair`. Scripts that called the old `kinuvo devices add` keep working but print a deprecation notice.

    That's everything for the notes. Want me to post the draft to the team channel, or keep it here while you review the table?
""".trimIndent()

private val MARKDOWN_REVIEW = """
    # Markdown review

    ## Text styles

    ### Inline formatting

    A paragraph with **bold**, *italic*, ~~strike~~, `inline code` and [a link](https://kinuvo.dev).

    ## Lists and code

    - Bullet one
    - Bullet two
        - Nested bullet
        - Another nested item

    1. First numbered
    2. Second numbered
        1. Nested number

    - [x] Completed task
    - [ ] Pending task

    > A blockquote that wraps onto another line on a phone.

    ```kotlin
    fun greet(name: String) {
        println("Hello, ${'$'}name!")
    }
    ```

    ## Wide table

    | Feature | iOS | Android | Notes |
    | --- | --- | --- | --- |
    | Markdown | Yes | Yes | This intentionally long cell checks wrapping and horizontal overflow on small phone screens. |
    | Composer | Source | Source | Dimmed markers |

    ---

    End of Markdown review.
""".trimIndent()

private val OLDER_SESSIONS = listOf(
    "Weekly groceries", "Collect documents for the 2026 tax return and check every deduction against last year's", "Garden watering schedule",
    "Recipes for a dinner party", "Book club reading list", "Car insurance renewal", "Moving checklist", "Gym plan",
    "Triage the crash reports from last night's beta build", "Sort holiday photos", "Monthly budget", "Draft an email to the landlord",
)

/**
 * Dense, realistic data for reviewing layouts: three Agents with many Sessions, a long mixed transcript with attachments,
 * tool results and Markdown on [agent]'s favorite "Release notes review" Session, a Board with every status, Routines,
 * Skills of both scopes and linked Knowledge notes. Browser tabs, Computers and Sessions that wait or run need a live
 * Server and are added by the test.
 */
suspend fun Scenario.showcase(agent: Agent) {
    val research = agent("research", "Research Assistant for Long-Running Investigations")
    val home = agent("home", "Home")

    session(agent, "Release notes review for the 2.0 launch, with the changelog and migration guide", favorite = true) {
        entry(SessionEntry.UserMessage("Draft the 2.0 release notes from this changelog.", attachments = listOf(Attachment("changelog", "CHANGELOG-2.0.pdf", "application/pdf", 284311))))
        assistant(RELEASE_NOTES, "m-notes")
        user("Check the migration script, the docs site and the release branch, then write the migration guide.")
        toolCall("t-read", "read_file", """{"path": "scripts/migrate.sh"}""")
        toolResult("t-read", "read_file", "#!/bin/sh\nkinuvo migrate \"$@\"")
        toolCall("t-fetch", "web_fetch", """{"url": "https://docs.example.com/2.0"}""")
        toolResult("t-fetch", "web_fetch", "Request failed: 503 Service Unavailable", isError = true)
        toolCall("t-push", "shell", """{"command": "git push origin release/2.0"}""")
        toolResult("t-push", "shell", "The user denied this call. Note: Don't push yet", isError = true)
        assistant(MIGRATION_GUIDE, "m-guide")
        user("Post it to the team channel.")
        toolCall("t-post", "slack_post", """{"channel": "#release", "text": "2.0 notes"}""")
        assistant(MARKDOWN_REVIEW, "m-markdown-review")
    }
    val slides = session(agent, "Prepare the quarterly planning slides") { user("Prepare the quarterly planning slides."); assistant("Drafted twelve slides.") }
    val trip = session(agent, "Trip plans", favorite = true) { user("Plan the trip."); assistant("Here is a draft itinerary.") }
    OLDER_SESSIONS.forEach { title -> session(agent, title) { user(title); assistant("Done.") } }
    val crash = session(agent, "Triage crash reports from the beta") { user("Triage the crash reports."); assistant("Three distinct crashes found.") }
    val contract = session(agent, "Contractor agreement review") { user("Review the agreement."); assistant("The payment terms look unusual.") }

    session(research, "Survey of on-device speech models") { user("Survey on-device speech models."); assistant("Found nine candidates.") }
    session(research, "Competitor pricing comparison") { user("Compare competitor pricing."); assistant("Pricing table drafted.") }
    session(research, "Primary sources for the history essay") { user("Find primary sources."); assistant("Four archives hold them.") }
    session(home, "Evening lights") { user("Dim the lights at eight."); assistant("Scheduled.") }

    val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
    fun due(days: Int): LocalDate = today.plus(DatePeriod(days = days))
    task("Renew passport before the summer trip, including new photos and the appointment", notes = "Bring the old passport and two photos.", due = due(-2))
    task("Book dentist appointment", due = due(1))
    task("Repot the plants")
    task("Prepare the quarterly planning slides", status = TaskStatus.InProgress, assignee = agent.id, due = due(3), notes = "Use last quarter's template; numbers are in the finance sheet.", sessions = listOf(slides))
    task("Triage crash reports from the beta", status = TaskStatus.InProgress, assignee = agent.id, due = due(0), sessions = listOf(crash, slides))
    task("Find a cheaper flight to Lisbon", status = TaskStatus.Waiting, assignee = research.id, due = due(2), notes = "Waiting on you: approve the booking under 120 EUR?")
    task("Review the contractor agreement and flag anything unusual in the payment terms", status = TaskStatus.Waiting, assignee = agent.id, due = due(-1), sessions = listOf(contract))
    task("Monthly budget", status = TaskStatus.Done, assignee = agent.id, due = due(-5))
    task("Car insurance renewal", status = TaskStatus.Done, notes = "Switched to the cheaper plan.", sessions = listOf(trip))
    task("Sort holiday photos", status = TaskStatus.Done, assignee = agent.id)
    task("Fix the garden watering timer", assignee = home.id, due = due(5))
    task("Email the landlord about the heating", due = due(0))

    routine(agent, "Morning brief", "Summarize my calendar, inbox and the weather.", Trigger.Cron("0 7 * * *"))
    routine(agent, "Standup notes", "Collect yesterday's commits and open reviews.", Trigger.Cron("30 9 * * 1-5"))
    routine(agent, "Weekly review of goals, habits and the reading list", "Review the week.", Trigger.Cron("0 18 * * 5"))
    routine(agent, "Inbox triage", "Archive newsletters, flag anything urgent.", Trigger.Every(120))
    routine(agent, "Backup check", "Check last night's backups finished.", Trigger.Cron("0 6 * * *"))
    routine(agent, "Flight price watch", "Watch Lisbon fares under 120 EUR.", Trigger.Every(60))

    skill(agent, "daily", "Daily planning instructions", "Plan the day.")
    skill(agent, "release-notes", "Turn a changelog into user-facing release notes with a migration section", "Write the notes.")
    skill(agent, "budget", "Categorize expenses and compare against the monthly plan", "Compare against the plan.")
    skill(agent, "travel", "Plan trips: flights, stays and a day-by-day itinerary", "Plan the trip.", ConfigScope.Shared)
    skill(agent, "code-review", "Review a diff for bugs, missing tests and unclear names", "Review the diff.", ConfigScope.Shared)

    note("human", "Fernando", "Maintains Kinuvo and Mockstro.")
    note("place", "Lisbon office", "Where the Kinuvo team meets each quarter.")
    note("meeting", "Kinuvo 2.0 planning", "Decided on incremental sync and voice chat.")
    note("idea", "Kinuvo sync without a Server", "Peer-to-peer sync between Devices on the same network.")
    note("project", "Kinuvo", "A self-hosted personal AI assistant, maintained by [[Fernando]].\n\n## Goals\n\n- Ship **2.0** with incremental sync\n- Voice chat on every platform\n- Keep the Server easy to run at home\n\n## Notes\n\nPlanning happened in [[Kinuvo 2.0 planning]] at the [[Lisbon office]]. The open question is [[Kinuvo sync without a Server]].\n\nThe mobile apps share one Compose UI; iOS and Android differ only in their platform shells, so most screens are tested once on JVM.",
        mapOf("status" to "Active", "owner" to "Fernando", "started" to "2025-03-01", "repository" to "github.com/FeernandoOFF/kinuvo", "tags" to "assistant, self-hosted, kotlin"))
}
