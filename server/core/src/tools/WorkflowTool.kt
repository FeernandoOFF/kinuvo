package kinuvo.server.tools

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.workflow.*
import kinuvo.server.Workflows
import kinuvo.server.WorkflowError
import kinuvo.server.throwing
import kinuvo.server.plugin.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

class WorkflowTool(private val workflows: Workflows, private val agentId: AgentId, private val sessionId: SessionId?) : CoreTool {
    override val descriptor = toolSpec(ToolNames.WORKFLOWS,
        "Save proven work from chat as a Workflow: cheap Tool steps (run_code, MCP, web_fetch), small Model requests, and a Review where the user checks data. " +
            "Actions: models (available providers/models and Agent default; optional Workflow id), agents, list, get, validate, save, run, status, cancel, retry, delete. validate checks without saving and returns Valid or all errors. save/validate take workflow JSON: id,name,agentId (defaults to you),inputs:[{name,description,default}],steps,edges:[{from,to,branch?}]. " +
            "Step type and fields: tool(id,tool,args), model(id,prompt,system?,model?), condition(id,left,op,right), review(id,text,value,surface?), approval(id,text), agent_turn(id,agentId,prompt,model?), text(id,text) whose output is its rendered text. " +
            "agentId is an Agent id from agents, not a display name; omitted Workflow agentId defaults to you. " +
            "model is {provider,model}; omit it to use the Agent default (or continued Session settings). Tool/Model/Condition need no Session. References: {{steps.ID.output[.path]}}, {{input.name}} and {{now}} (ISO time in the Workflow's timeZone; .date .time .weekday .timezone .utc .timestamp); exact argument references keep JSON types. " +
            "Condition ops: Equals,NotEquals,Contains,NotContains,Empty,NotEmpty,GreaterThan,LessThan. branch true/false edges leave Conditions; unused branches skip. " +
            "Review flattens small JSON arrays of flat objects into repeated fields (fixed item count). Optional surface is an A2UI createSurface spec, rendered with references and submitted through show_ui; output is the submitted dataModel JSON. run/retry wait up to wait_seconds (default 60, max 120); return the run id when still running or waiting for Review/Approval. Workflow Tool steps cannot start runs.",
        requiredParameters = listOf(ToolParameter("action", "models|agents|list|get|validate|save|run|status|cancel|retry|delete", ParameterType.String)),
        optionalParameters = listOf(
            ToolParameter("id", "Workflow id; run id for status/cancel/retry", ParameterType.String),
            ToolParameter("workflow", "Workflow JSON object for save/validate", ParameterType.Object(emptyList(), additionalProperties = true)),
            ToolParameter("input", "Run input values", ParameterType.Object(emptyList(), additionalProperties = true)),
            ToolParameter("wait_seconds", "Seconds to wait, 0 to return immediately", ParameterType.Float),
        ))

    override fun assess(args: JsonObject): Assessment {
        val read = args.string("action") in setOf("models", "agents", "list", "get", "status", "validate")
        return Assessment(listOf(Action(if (read) Risk.Low else Risk.High, listOf(descriptor.name),
            if (read) null else "Changes or runs a Workflow", trustable = false, alwaysAsk = !read)))
    }

    override suspend fun bind(args: JsonObject): suspend () -> Unit {
        require(sessionId != null || args.string("action") !in setOf("run", "retry")) { "Workflow Tool steps cannot start Workflow runs" }
        return {}
    }

    override suspend fun execute(args: JsonObject): String = throwing<WorkflowError, String> {
        val action = args.requireString("action")
        fun id() = args.requireString("id")
        // The graph is the caller's own; repeating it in every result would only cost tokens.
        fun encode(run: WorkflowRun) = JsonObject(ProtocolJson.encodeToJsonElement(WorkflowRun.serializer(), run).jsonObject - "workflow").toString()
        when (action) {
            "models" -> buildJsonObject {
                put("providers", ProtocolJson.encodeToJsonElement(workflows.models().providers))
                val owner = args.string("id")?.let { workflows.get(WorkflowId(it)).agentId } ?: agentId
                put("default", ProtocolJson.encodeToJsonElement(workflows.defaultModel(owner)))
            }.toString()
            "agents" -> JsonArray(workflows.agents().map { agent -> buildJsonObject {
                put("id", agent.id.value); put("name", agent.name)
                if (agent.id == agentId) put("you", true)
            } }).toString()
            "list" -> JsonArray(workflows.list().map { w -> buildJsonObject {
                put("id", w.id.value); put("name", w.name); put("description", w.description)
                put("inputs", ProtocolJson.encodeToJsonElement(w.inputs)); put("trigger", ProtocolJson.encodeToJsonElement(w.trigger)); put("stepCount", w.steps.size)
            } }).toString()
            "get" -> ProtocolJson.encodeToString(Workflow.serializer(), workflows.get(WorkflowId(id())))
            "save", "validate" -> {
                val w = ProtocolJson.decodeFromJsonElement(Workflow.serializer(), requireNotNull(args["workflow"]) { "Give workflow" })
                val candidate = w.copy(agentId = w.agentId ?: agentId)
                if (action == "validate") { workflows.validate(candidate); return@throwing "Valid" }
                val saved = workflows.save(candidate, workflows.list().none { it.id == w.id })
                ProtocolJson.encodeToString(Workflow.serializer(), saved)
            }
            "status" -> encode(workflows.runOf(WorkflowRunId(id())))
            "cancel" -> encode(workflows.cancel(WorkflowRunId(id())))
            "delete" -> { workflows.delete(WorkflowId(id())); "Deleted Workflow" }
            "run", "retry" -> {
                require(sessionId != null) { "Workflow Tool steps cannot start Workflow runs" }
                var run = if (action == "retry") workflows.retry(WorkflowRunId(id())) else workflows.run(WorkflowId(id()), args["input"] as? JsonObject ?: JsonObject(emptyMap()))
                val wait = (args["wait_seconds"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 120.0) ?: 60.0
                val start = TimeSource.Monotonic.markNow()
                while (run.status == WorkflowRunStatus.Running && run.steps.values.none { it.status == StepStatus.WaitingApproval } && start.elapsedNow() < wait.seconds) {
                    delay(250); run = workflows.runOf(run.id)
                }
                encode(run)
            }
            else -> error("Unknown Workflow action '$action'")
        }
    }
}
