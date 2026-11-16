package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kinuvo.protocol.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class WorkflowCommands : Group(
    "workflows", "Manage Workflows, graphs of steps the Server runs.",
    ListWorkflows(), ShowWorkflow(), ImportWorkflow(), RunWorkflowCommand(), WorkflowRunsCommand(), RemoveWorkflow(),
)

private val pretty = Json(ProtocolJson) { prettyPrint = true }

private class ListWorkflows : ServerCommand("list", "List Workflows.") {
    override suspend fun run() {
        val workflows = session.call { client.call(Api.Workflows, Unit) }
        echo(table(listOf("ID", "NAME", "STEPS", "TRIGGER", "AGENT"), workflows.map { w ->
            listOf(w.id.value, w.name, w.steps.size.toString(), if (!w.enabled) "paused" else w.trigger?.describe() ?: "manual", w.agentId?.value ?: "-")
        }))
    }
}

private class ShowWorkflow : ServerCommand("show", "Print a Workflow as JSON, as `import` reads it.") {
    private val id by argument("ID")

    override suspend fun run() = echo(pretty.encodeToString(Workflow.serializer(), session.call { client.call(Api.Workflow, WorkflowId(id)) }))
}

private class ImportWorkflow : ServerCommand("import", "Create a Workflow from a JSON file (- reads stdin); with -a, its Agent steps run as that Agent.") {
    private val file by argument("FILE")
    private val replace by option("--replace", help = "Update the Workflow if it already exists").flag()

    override suspend fun run() {
        val workflow = try { ProtocolJson.decodeFromString(Workflow.serializer(), if (file == "-") textOrStdin(file) else readText(file)) }
        catch (e: SerializationException) { throw CliktError("Not a Workflow: ${e.message}") }
        val saved = session.call {
            val known = client.call(Api.Agents, Unit).map { it.id }
            val fallback = agentId()
            fun AgentId.adapted() = requestedAgent ?: takeIf { it in known } ?: fallback
            val adapted = workflow.copy(agentId = workflow.agentId?.adapted(),
                steps = workflow.steps.map { if (it is WorkflowStep.AgentTurn) it.copy(agentId = it.agentId.adapted()) else it })
            val exists = client.call(Api.Workflows, Unit).any { it.id == adapted.id }
            client.call(if (replace && exists) Api.UpdateWorkflow else Api.CreateWorkflow, adapted)
        }
        echo("${if (replace) "Saved" else "Created"} workflow ${saved.id}.")
    }
}

private class RunWorkflowCommand : ServerCommand("run", "Start a run; it continues on the Server, see `workflows runs`.") {
    private val id by argument("ID")

    override suspend fun run() {
        val run = session.call { client.call(Api.RunWorkflow, WorkflowId(id)) }
        echo("Started run ${run.id}.")
    }
}

private class WorkflowRunsCommand : ServerCommand("runs", "Show the latest runs and the status of each step.") {
    private val id by argument("ID")

    override suspend fun run() {
        val runs = session.call { client.call(Api.WorkflowRuns, WorkflowId(id)) }.take(5)
        echo(runs.joinToString("\n\n") { r ->
            "${r.id}  ${r.status.name.lowercase()}  ${time(r.startedAt, r.workflow.timeZone)}\n" + table(listOf("STEP", "STATUS", "OUTPUT OR ERROR"), r.workflow.steps.map { s ->
                val step = r.steps[s.id]
                listOf(s.id.value, step?.status?.name?.lowercase().orEmpty(), (step?.error ?: step?.output).orEmpty().oneLine(80))
            })
        }.ifEmpty { "No runs." })
    }
}

private class RemoveWorkflow : ServerCommand("remove", "Delete a Workflow.") {
    private val id by argument("ID")

    override suspend fun run() {
        session.call { client.call(Api.DeleteWorkflow, WorkflowId(id)) }
        echo("Removed $id.")
    }
}

private fun String.oneLine(max: Int) = replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max - 1) + "…" else it }
