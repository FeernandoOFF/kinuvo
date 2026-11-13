package kinuvo.plugins.apple

import kinuvo.harness.ToolSpec
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.toolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** One Swift core [method] as a tool: the model's arguments (blank ones dropped, as some providers send every optional one) go in as the method's parameters. */
private class AppleTool(
    override val descriptor: ToolSpec,
    private val apple: Apple,
    private val method: String,
    private val render: (JsonElement) -> String,
) : CoreTool {
    override suspend fun execute(args: JsonObject) =
        render(apple.call(method, JsonObject(args.filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })))
}

private fun text(name: String, description: String) = ToolParameter(name, description, ParameterType.String)
private fun flag(name: String, description: String) = ToolParameter(name, description, ParameterType.Boolean)
private val LIMIT = ToolParameter("limit", "Most results to return (default 50)", ParameterType.Integer)
private const val DATE = "YYYY-MM-DD, or YYYY-MM-DDTHH:MM in the Server's time zone"

internal fun reminderTools(apple: Apple): List<Tool> = listOf(
    AppleTool(toolSpec("reminders_lists", "The user's Apple Reminders lists."), apple, "reminders.lists") { lists<AppleList>(it) { l -> "- ${l.id} \"${l.title}\"" } },
    AppleTool(toolSpec("reminders_list", "Apple Reminders, open ones unless include_done is true, soonest due first.",
        optionalParameters = listOf(text("list", "List name or id; all lists when omitted"), flag("include_done", "Include completed reminders"), LIMIT)), apple, "reminders.list") {
        lists<AppleReminder>(it, "No reminders.") { r -> r.describe() }
    },
    AppleTool(toolSpec("reminders_get", "One Apple Reminder.", listOf(text("id", "Reminder id"))), apple, "reminders.get") { one<AppleReminder>(it) { r -> r.describe() } },
    AppleTool(toolSpec("reminders_create", "Add an Apple Reminder.", listOf(text("title", "Reminder title")),
        listOf(text("list", "List name or id; defaults to the user's default list"), text("notes", "Notes"), text("due", "Due: $DATE"))), apple, "reminders.create") {
        "Created.\n" + one<AppleReminder>(it) { r -> r.describe() }
    },
    AppleTool(toolSpec("reminders_update", "Change, complete or reopen an Apple Reminder.", listOf(text("id", "Reminder id")),
        listOf(text("title", "New title"), text("notes", "New notes"), text("due", "Due: $DATE; \"none\" removes it"), flag("done", "true completes it, false reopens it"))), apple, "reminders.update") {
        "Updated.\n" + one<AppleReminder>(it) { r -> r.describe() }
    },
)

internal fun calendarTools(apple: Apple): List<Tool> = listOf(
    AppleTool(toolSpec("calendar_list", "The user's Apple Calendar calendars."), apple, "calendar.list") {
        lists<AppleList>(it) { c -> "- ${c.id} \"${c.title}\"" + if (c.writable) "" else " (read-only)" }
    },
    AppleTool(toolSpec("calendar_events", "Apple Calendar events from start to end, earliest first; query keeps those whose title, location or notes contain it.",
        optionalParameters = listOf(text("start", "From: $DATE; defaults to today"), text("end", "Until: $DATE (a date means through that day); defaults to 7 days after start"),
            text("query", "Words to find"), text("calendar", "Calendar name or id; all calendars when omitted"), LIMIT)), apple, "calendar.events") {
        lists<AppleEvent>(it, "No events.") { e -> e.describe() }
    },
    AppleTool(toolSpec("calendar_event_create", "Add an Apple Calendar event.", listOf(text("title", "Event title"), text("start", "Start: $DATE")),
        listOf(text("end", "End: $DATE; defaults to an hour after start, or the same day when all_day"), flag("all_day", "An all-day event (default when start is a date only)"),
            text("location", "Location"), text("notes", "Notes"), text("calendar", "Calendar name or id; defaults to the user's default calendar"))), apple, "calendar.create") {
        "Created.\n" + one<AppleEvent>(it) { e -> e.describe() }
    },
    AppleTool(toolSpec("calendar_event_update", "Change an Apple Calendar event; for a repeating one only the occurrence given by occurrence changes.", listOf(text("id", "Event id")),
        listOf(text("occurrence", "Start of the occurrence of a repeating event, as listed"), text("title", "New title"), text("start", "New start: $DATE"), text("end", "New end: $DATE"),
            flag("all_day", "Make it an all-day event or not"), text("location", "New location"), text("notes", "New notes"), text("calendar", "Move to this calendar (name or id)"))), apple, "calendar.update") {
        "Updated.\n" + one<AppleEvent>(it) { e -> e.describe() }
    },
    AppleTool(toolSpec("calendar_event_delete", "Delete an Apple Calendar event; for a repeating one only the occurrence given by occurrence goes.", listOf(text("id", "Event id")),
        listOf(text("occurrence", "Start of the occurrence of a repeating event, as listed"))), apple, "calendar.delete") { "Deleted." },
)

private inline fun <reified T> lists(element: JsonElement, empty: String = "None.", noinline line: (T) -> String) =
    AppleJson.decodeFromJsonElement<List<T>>(element).joinToString("\n", transform = line).ifEmpty { empty }

private inline fun <reified T> one(element: JsonElement, line: (T) -> String) = line(AppleJson.decodeFromJsonElement<T>(element))

/** One line, then its notes indented. */
private fun AppleReminder.describe() = buildString {
    append("- $id \"$title\" [${if (done) "done" else "open"}] in $list")
    due?.let { append(", due $it") }
    if (notes.isNotBlank()) append("\n  ${notes.trim().replace("\n", "\n  ")}")
}

private fun AppleEvent.describe() = buildString {
    append("- $id \"$title\" ${if (all_day) (if (start == end) start else "$start to $end") else "$start to $end"}")
    if (all_day) append(" (all day)")
    if (location.isNotBlank()) append(" at $location")
    append(" in $calendar")
    if (recurring) append(", repeats")
    if (notes.isNotBlank()) append("\n  ${notes.trim().replace("\n", "\n  ")}")
}
