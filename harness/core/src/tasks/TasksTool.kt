package kinuvo.harness.core.tasks

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.Agent
import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.protocol.TaskInfo
import kinuvo.protocol.TaskStatus
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The user's Tasks on the Board, for the Agent to list, create and update. */
class TasksTool(private val tasks: Tasks, private val agent: Agent, private val taskId: String? = null) : Tool {
    override val risk = Risk.Low

    override val descriptor = ToolDescriptor(
        name = ToolNames.TASKS,
        description = taskId?.let {
            "Your assigned Task $it. list shows this Task; update changes its title, due date, assignee or status. " +
                "Set status done when finished, or set status waiting when you need the user. Put the result or what you need in your final reply; it is saved to the Task's notes."
        } ?: ("The user's tasks (from their task app). list shows open tasks (mine=true: only those assigned to you); " +
            "create adds one; update changes title, due date, assignee or status (todo, in_progress, waiting = needs the user, " +
            "done). When you finish a task, set it done; " +
            "when you need something from the user, set it waiting and explain what in your reply."),
        requiredParameters = listOf(
            ToolParameterDescriptor("action", if (taskId == null) "list | create | update" else "list | update", ToolParameterType.Enum(if (taskId == null) arrayOf("list", "create", "update") else arrayOf("list", "update"))),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("id", "Task id (update)", ToolParameterType.String),
            ToolParameterDescriptor("mine", "true to list only tasks assigned to you", ToolParameterType.String),
            ToolParameterDescriptor("title", "Task title (create, update)", ToolParameterType.String),
            ToolParameterDescriptor("list", "List name (create); defaults to the user's default list", ToolParameterType.String),
            ToolParameterDescriptor("notes", "Notes (create)", ToolParameterType.String),
            ToolParameterDescriptor("due", "Due date, YYYY-MM-DD; \"none\" removes it (create, update)", ToolParameterType.String),
            ToolParameterDescriptor("assignee", "Agent id to assign; \"none\" unassigns (create, update)", ToolParameterType.String),
            ToolParameterDescriptor("status", "todo | in_progress | waiting | done (update)", ToolParameterType.String),
        ),
    )

    // Some providers send every optional parameter, blank when unused.
    override suspend fun execute(args: JsonObject): String {
        val a = JsonObject(args.filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })
        val action = a.requireString("action")
        if (taskId != null) {
            require(action in listOf("list", "update")) { "This run can only manage its assigned Task" }
            require(action == "list" || a.requireString("id") == taskId) { "This run can only manage Task $taskId" }
        }
        return when (action) {
            "list" -> (if (taskId == null) tasks.all() else listOf(tasks.get(taskId))).filter { a.string("mine") != "true" || it.assignee == agent.id }
                .joinToString("\n") { it.describe() }.ifEmpty { "No open tasks." }
            "create" -> tasks.create(
                a.requireString("title"), a.string("list"), a.string("notes").orEmpty(),
                a.string("due")?.takeIf { it != "none" }?.let(::date), a.string("assignee")?.let { if (it == "none") "" else it },
            ).let { "Created.\n${it.describe()}" }
            "update" -> tasks.update(
                a.requireString("id"), a.string("title"), a.string("status")?.let(::status),
                a.string("assignee")?.let { if (it == "none") "" else it }, a.string("due")?.let { if (it == "none") "" else date(it).toString() },
            ).let { "Updated.\n${it.describe()}" }
            else -> error("Unknown action '$action'")
        }
    }

    private fun date(text: String) = runCatching { LocalDate.parse(text) }.getOrElse { throw IllegalArgumentException("due must look like 2026-05-01") }

    private fun status(text: String) = when (text) {
        "todo" -> TaskStatus.Todo
        "in_progress" -> TaskStatus.InProgress
        "waiting" -> TaskStatus.Waiting
        "done" -> TaskStatus.Done
        else -> error("status must be todo, in_progress, waiting or done")
    }
}

/** One line per Task, then its notes indented. */
fun TaskInfo.describe(): String = buildString {
    append("- $id \"$title\" [${status.name.lowercase()}] in $listName")
    due?.let { append(", due $it") }
    append(assignee?.let { ", assigned to $it" } ?: ", unassigned")
    if (notes.isNotBlank()) append("\n  ${notes.trim().replace("\n", "\n  ")}")
}
