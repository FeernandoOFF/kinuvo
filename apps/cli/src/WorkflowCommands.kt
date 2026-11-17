package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kinuvo.protocol.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class WorkflowCommands : Group(
    "workflows", "Manage Workflows, graphs of steps the Server runs.",
    ListWorkflows(), ShowWorkflow(), ImportWorkflow(), RunWorkflowCommand(), RetryWorkflowCommand(), WorkflowRunsCommand(), RemoveWorkflow(),
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

private class RunWorkflowCommand : ServerCommand("run", "Start a run; it continues on the Server, see `workflows runs`. With --wait, wait for it to end and print its result.") {
    private val id by argument("ID")
    private val json by option("--input", help = "The run's inputs as a JSON object")
    private val values by option("-i", "--set", metavar = "NAME=VALUE", help = "One input; repeat for more").multiple()
    private val wait by option("--wait", help = "Wait until the run ends; fails unless it ends done").flag()

    override suspend fun run() {
        val given = try { json?.let { Json.parseToJsonElement(it) as? JsonObject ?: throw CliktError("--input must be a JSON object") } } catch (e: SerializationException) { throw CliktError("--input is not JSON: ${e.message}") }
        val input = JsonObject(given.orEmpty() + values.associate { pair ->
            val (name, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: throw CliktError("Give inputs as NAME=VALUE")
            name to inputValue(value)
        })
        val ended = session.call {
            val started = client.call(Api.RunWorkflow, RunWorkflowParams(WorkflowId(id), input))
            echo("Started run ${started.id}.")
            if (!wait) return@call null
            var asked = false
            while (true) {
                // Well under the Server's message rate limit.
                delay(1000)
                val now = client.call(Api.WorkflowRuns, WorkflowId(id)).first { it.id == started.id }
                if (now.status != WorkflowRunStatus.Running) return@call now
                if (!asked && now.steps.values.any { it.status == StepStatus.WaitingApproval }) { asked = true; echo("Waiting for you in the app.") }
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: return
        echo(ended.describe())
        ended.output?.let { echo("\n$it") }
        if (ended.status != WorkflowRunStatus.Done) throw ProgramResult(1)
    }
}

private class RetryWorkflowCommand : ServerCommand("retry", "Run a failed or cancelled run again from the step that stopped it, keeping what its finished steps produced.") {
    private val runId by argument("RUN_ID")

    override suspend fun run() {
        val run = session.call { client.call(Api.RetryWorkflow, WorkflowRunId(runId)) }
        echo("Started run ${run.id}.")
    }
}

private fun WorkflowRun.describe() = "$id  ${status.name.lowercase()}  ${time(startedAt, workflow.timeZone)}\n" + table(listOf("STEP", "STATUS", "OUTPUT OR ERROR"), workflow.steps.map { s ->
    val step = steps[s.id]
    listOf(s.id.value, step?.status?.name?.lowercase().orEmpty(), (step?.error ?: step?.output).orEmpty().oneLine(80))
})

private class WorkflowRunsCommand : ServerCommand("runs", "Show the latest runs and the status of each step.") {
    private val id by argument("ID")

    override suspend fun run() {
        val runs = session.call { client.call(Api.WorkflowRuns, WorkflowId(id)) }.take(5)
        echo(runs.joinToString("\n\n") { it.describe() }.ifEmpty { "No runs." })
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
