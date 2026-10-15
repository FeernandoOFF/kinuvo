package kinuvo.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import kinuvo.protocol.*

internal class TaskCommands : Group(
    "tasks", "The Board: your Tasks from your task app (config.yaml) and the Agents working on them.",
    ListTasks(), TaskLists(), AddTask(), AssignTask(), SetTaskStatus(),
) {
    override fun aliases() = mapOf("ls" to listOf("list"))
}

private class ListTasks : ServerCommand("list", "List open Tasks.") {
    private val all by option("--all", help = "Include done Tasks").flag()

    override suspend fun run() {
        val tasks = session.call { client.call(Api.Tasks, TasksParams(all)) }
        echo(table(listOf("ID", "LIST", "STATUS", "AGENT", "DUE", "TITLE"), tasks.map { t ->
            listOf(t.id.take(8), t.listName, t.status.label(), t.assignee ?: "-", t.due ?: "-", t.title)
        }))
    }
}

private class TaskLists : ServerCommand("lists", "List the task lists the Board shows and their Agents.") {
    override suspend fun run() {
        val lists = session.call { client.call(Api.TaskLists, Unit) }
        echo(table(listOf("LIST", "AGENT"), lists.map { listOf(it.name, it.agentId ?: "-") }))
    }
}

private class AddTask : ServerCommand("add", "Add a Task; @agent in the title assigns it.") {
    private val title by argument("TITLE")
    private val list by option("--list", help = "List name (default: config.yaml's tasks.defaultList)")
    private val due by option("--due", help = "Due date, e.g. 2026-11-20")
    private val notes by option("--notes")
    private val assignee by option("--agent", help = "Agent to assign")

    override suspend fun run() {
        val t = session.call { client.call(Api.CreateTask, CreateTaskParams(title, list, notes.orEmpty(), due, assignee)) }
        echo("Added ${t.id.take(8)} to ${t.listName}" + (t.assignee?.let { ", assigned to $it" } ?: ""))
    }
}

private class AssignTask : ServerCommand("assign", "Assign a Task to an Agent; none unassigns it.") {
    private val id by argument("ID")
    private val agent by argument("AGENT")

    override suspend fun run() {
        val t = session.call { client.call(Api.UpdateTask, UpdateTaskParams(taskId(id), assignee = if (agent == "none") "" else agent)) }
        echo("${t.title}: ${t.assignee ?: "unassigned"}")
    }
}

private class SetTaskStatus : ServerCommand("status", "Move a Task: todo (an Agent picks it up again), in-progress, waiting or done.") {
    private val id by argument("ID")
    private val status by argument("STATUS").enum<TaskStatus> { it.label() }

    override suspend fun run() {
        val t = session.call { client.call(Api.UpdateTask, UpdateTaskParams(taskId(id), status = status)) }
        echo("${t.title}: ${t.status.label()}")
    }
}

private fun TaskStatus.label() = when (this) {
    TaskStatus.Todo -> "todo"
    TaskStatus.InProgress -> "in-progress"
    TaskStatus.Waiting -> "waiting"
    TaskStatus.Done -> "done"
}

/** The full id of the Task whose id starts with [prefix]. */
private suspend fun Calls.taskId(prefix: String): String =
    client.call(Api.Tasks, TasksParams(includeDone = true)).filter { it.id.startsWith(prefix) }.singleOrNull()?.id ?: error("No single task matches '$prefix'")
