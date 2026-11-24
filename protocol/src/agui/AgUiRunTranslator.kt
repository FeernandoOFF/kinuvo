package kinuvo.protocol.agui

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.QuestionKind
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.RunId
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What one [AgentEvent] becomes on the wire: an AG-UI event of the active run, or a Kinuvo item that has no run counterpart. */
sealed interface AgUiOutput {
    data class Run(val event: AgUiEvent) : AgUiOutput
    data class Item(val event: AgentEvent) : AgUiOutput
}

/**
 * Folds one Session's [AgentEvent]s into AG-UI 1.0 run events (ADR 0042). Every text, reasoning and tool call it opens
 * is closed before the run ends. Events outside a run, and ones with no AG-UI counterpart, come back as [AgUiOutput.Item].
 *
 * A run that waits for answers ends with the interrupt outcome once no sibling tool call is still running. The execution
 * itself keeps going, so its output after the answers opens a new run (`<runId>~<n>`) and is preceded by `RUN_STARTED`;
 * output that arrives while the answers are still open waits until then. [stopping] marks Stop: a run that was waiting
 * is not reopened, and its end is reported as items.
 */
class AgUiRunTranslator(private val sessionId: SessionId, private val parentRunId: RunId? = null) {
    private var runId: RunId? = null
    private var text: String? = null
    private var reasoning: String? = null
    private val pending = LinkedHashMap<InteractionId, PendingInteraction>()
    private val children = mutableSetOf<InteractionId>()
    private val answered = mutableSetOf<String>()
    private val openTools = mutableSetOf<String>()
    private var activities = 0
    private var base: String? = null
    private var resumed = 0
    private var parked = false
    private var stopping = false
    private val interrupted = mutableSetOf<InteractionId>()
    private val deferred = mutableListOf<AgentEvent>()

    val active: Boolean get() = runId != null

    /** Stop was requested: the run is not reopened for what the stopped work still reports. */
    fun stopping() { if (active || parked) stopping = true }

    fun on(event: AgentEvent): List<AgUiOutput> = buildList { fold(event, this) }

    private fun fold(event: AgentEvent, out: MutableList<AgUiOutput>) {
        fun run(vararg events: AgUiEvent) = events.forEach { out.add(AgUiOutput.Run(it)) }
        fun item() = out.add(AgUiOutput.Item(event))
        val output = event is AgentEvent.TextMessageContent || event is AgentEvent.TextMessageEnd || event is AgentEvent.ThinkingContent ||
            event is AgentEvent.ToolCallStart || event is AgentEvent.ToolCallResult || event is AgentEvent.Compacted || event is AgentEvent.Warning
        val terminal = event is AgentEvent.RunFinished || event is AgentEvent.RunError
        val wasParked = runId == null && parked
        if (runId == null && parked && event !is AgentEvent.RunStarted) {
            val waiting = interrupted.any { it in pending }
            if (output && !stopping) {
                if (waiting) { deferred += event; return }
                resume(out)
            } else if (terminal && !stopping && !waiting) resume(out)
            else if (terminal || event is AgentEvent.Idle || event is AgentEvent.ExecutionPhaseChanged &&
                (event.phase == ExecutionPhase.Idle || event.phase == ExecutionPhase.Lost)) reset()
        }
        val open = runId
        when (event) {
            is AgentEvent.RunStarted -> {
                if (open != null) { closeItems(::run); run(AgUiEvent.RunError("Run ${open.value} was superseded by ${event.runId.value}")) }
                val late = deferred.toList()
                reset()
                runId = event.runId
                base = event.runId.value
                run(AgUiEvent.RunStarted(sessionId.value, event.runId.value, parentRunId = parentRunId?.value))
                late.forEach { fold(it, out) }
            }
            is AgentEvent.TextMessageContent -> if (open == null) item() else {
                closeReasoning(::run)
                if (text != event.messageId) { closeText(::run); text = event.messageId; run(AgUiEvent.TextMessageStart(event.messageId)) }
                run(AgUiEvent.TextMessageContent(event.messageId, event.delta))
            }
            is AgentEvent.TextMessageEnd -> if (open == null) item() else {
                closeReasoning(::run)
                if (text != event.messageId) {
                    closeText(::run)
                    run(AgUiEvent.TextMessageStart(event.messageId), AgUiEvent.TextMessageContent(event.messageId, event.text))
                }
                text = null
                run(AgUiEvent.TextMessageEnd(event.messageId, kinuvo { put("text", event.text) }))
            }
            is AgentEvent.ThinkingContent -> if (open == null) item() else {
                closeText(::run)
                if (reasoning != event.messageId) {
                    closeReasoning(::run)
                    reasoning = event.messageId
                    run(AgUiEvent.ReasoningStart(event.messageId), AgUiEvent.ReasoningMessageStart(event.messageId))
                }
                run(AgUiEvent.ReasoningMessageContent(event.messageId, event.delta))
            }
            is AgentEvent.ToolCallStart -> if (open == null) item() else {
                closeText(::run)
                closeReasoning(::run)
                openTools += event.toolCallId.value
                val meta = kinuvo {
                    put("args", event.args)
                    event.identity?.let { put("identity", ProtocolJson.encodeToJsonElement(it)) }
                }
                val id = event.toolCallId.value
                run(
                    AgUiEvent.ToolCallStart(id, event.toolCallName, metadata = meta),
                    AgUiEvent.ToolCallArgs(id, event.args, meta),
                    AgUiEvent.ToolCallEnd(id, meta),
                )
            }
            is AgentEvent.ToolCallResult -> if (open == null) item() else {
                val id = event.toolCallId.value
                val meta = kinuvo {
                    put("isError", event.isError)
                    event.identity?.let { put("identity", ProtocolJson.encodeToJsonElement(it)) }
                }
                if (answered.add(id)) run(AgUiEvent.ToolCallResult("result:$id", id, event.content, meta))
                else run(AgUiEvent.ActivitySnapshot("progress:$id", "kinuvo:tool-progress", buildJsonObject {
                    put("toolCallId", id)
                    put("content", event.content)
                }, metadata = meta))
                openTools -= id
                interrupt(::run)
            }
            is AgentEvent.Compacted -> if (open == null) item() else run(activity("kinuvo:compaction", "summary" to event.summary))
            is AgentEvent.Warning -> if (open == null) item() else run(activity("kinuvo:warning", "message" to event.message))
            is AgentEvent.InteractionRequested -> {
                pending[event.interaction.id] = event.interaction
                if (event.childSessionId != null) children += event.interaction.id
                item()
                interrupt(::run)
            }
            is AgentEvent.InteractionResolved -> {
                pending.remove(event.id)
                children -= event.id
                item()
            }
            is AgentEvent.InteractionStaged, is AgentEvent.QueueChanged, is AgentEvent.SubAgentChanged, is AgentEvent.ExecutionEntryChanged, is AgentEvent.ReflectionStarted -> item()
            is AgentEvent.Idle -> {
                if (open != null) { closeItems(::run); runId = null; run(AgUiEvent.RunFinished(sessionId.value, open.value, RunOutcome.Cancelled)) }
                reset()
                pending.keys.retainAll(children)
                item()
            }
            is AgentEvent.ExecutionPhaseChanged -> when (event.phase) {
                ExecutionPhase.Running -> if (open == null) item()
                ExecutionPhase.RequiresAction -> if (open == null) item() else interrupt(::run)
                ExecutionPhase.Lost -> if (open == null) { reset(); pending.clear(); children.clear(); item() } else {
                    closeItems(::run)
                    runId = null
                    reset()
                    pending.clear()
                    children.clear()
                    run(AgUiEvent.RunError("Execution lost", "kinuvo:lost"))
                }
                ExecutionPhase.Idle -> if (open == null) item()
            }
            is AgentEvent.RunFinished -> if (open == null) { endOutsideRun(event, wasParked, out) } else {
                closeItems(::run)
                runId = null
                reset()
                pending.keys.retainAll(children)
                run(AgUiEvent.RunFinished(sessionId.value, open.value, if (event.cancelled) RunOutcome.Cancelled else RunOutcome.Success()))
            }
            is AgentEvent.RunError -> if (open == null) { endOutsideRun(event, wasParked, out) } else {
                closeItems(::run)
                runId = null
                reset()
                pending.keys.retainAll(children)
                run(AgUiEvent.RunError(event.message))
            }
        }
    }

    /** The execution ended with no wire run open: a parked one resolves its interactions as items, then reports Idle. */
    private fun endOutsideRun(event: AgentEvent, wasParked: Boolean, out: MutableList<AgUiOutput>) {
        stopping = false
        if (wasParked) pending.values.filter { it.id !in children }.forEach { out.add(AgUiOutput.Item(AgentEvent.InteractionResolved(it.id))) }
        pending.keys.retainAll(children)
        out.add(AgUiOutput.Item(event))
        if (wasParked) out.add(AgUiOutput.Item(AgentEvent.Idle))
    }

    /** Ends the active run with the interrupt outcome when it has open interactions and nothing else is still running. */
    private fun interrupt(emit: (AgUiEvent) -> Unit) {
        val open = runId ?: return
        val waiting = pending.values.filter { it.id !in children }
        if (waiting.isEmpty()) return
        val parkedTools = waiting.mapNotNullTo(mutableSetOf()) { (it as? PendingInteraction.Approval)?.toolCallId?.value
            ?: (it as? PendingInteraction.Questions)?.toolCallId?.value ?: (it as? PendingInteraction.Connect)?.toolCallId?.value }
        if ((openTools - parkedTools).isNotEmpty()) return
        closeItems(emit)
        runId = null
        parked = true
        interrupted.clear()
        interrupted += waiting.map { it.id }
        emit(AgUiEvent.RunFinished(sessionId.value, open.value, RunOutcome.Interrupted(waiting.map { it.toInterrupt() })))
    }

    /** Opens the run that continues the parked one, then the output that was waiting for it. */
    private fun resume(out: MutableList<AgUiOutput>) {
        val late = deferred.toList()
        deferred.clear()
        val next = RunId("${base}~${++resumed}")
        parked = false
        interrupted.clear()
        runId = next
        out.add(AgUiOutput.Run(AgUiEvent.RunStarted(sessionId.value, next.value, parentRunId = parentRunId?.value)))
        late.forEach { fold(it, out) }
    }

    private fun reset() {
        parked = false
        stopping = false
        resumed = 0
        interrupted.clear()
        deferred.clear()
        openTools.clear()
    }

    private fun closeItems(emit: (AgUiEvent) -> Unit) {
        closeReasoning(emit)
        closeText(emit)
    }

    private fun closeText(emit: (AgUiEvent) -> Unit) {
        text?.let { emit(AgUiEvent.TextMessageEnd(it)) }
        text = null
    }

    private fun closeReasoning(emit: (AgUiEvent) -> Unit) {
        reasoning?.let { emit(AgUiEvent.ReasoningMessageEnd(it)); emit(AgUiEvent.ReasoningEnd(it)) }
        reasoning = null
    }

    private fun activity(type: String, field: Pair<String, String>) =
        AgUiEvent.ActivitySnapshot("activity:${++activities}", type, buildJsonObject { put(field.first, field.second) })

    private fun kinuvo(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject { put("kinuvo", buildJsonObject(build)) }

    private fun PendingInteraction.toInterrupt(): Interrupt = when (this) {
        is PendingInteraction.Approval -> Interrupt(
            id.value, "tool_call", reason ?: "Allow $toolCallName?", toolCallId.value, approvalSchema,
            kinuvo {
                put("toolCallName", toolCallName)
                put("risk", risk.name.lowercase())
                put("trustable", trustable)
                put("args", args)
                trustHint?.let { put("trustHint", it) }
                identity?.let { put("identity", ProtocolJson.encodeToJsonElement(it)) }
                preview?.let { put("preview", ProtocolJson.encodeToJsonElement(it)) }
            },
        )
        is PendingInteraction.Questions -> Interrupt(
            id.value, "input_required", title, toolCallId.value, questionsSchema(this),
            screen?.let { s -> kinuvo { put("screen", ProtocolJson.encodeToJsonElement(s)) } },
        )
        is PendingInteraction.HarnessApproval -> Interrupt(
            id.value, "kinuvo:harness_approval", title, metadata = kinuvo {
                description?.let { put("description", it) }
                site?.let { put("site", it) }
                server?.let { put("server", it) }
                put("options", ProtocolJson.encodeToJsonElement(options))
            },
            responseSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("optionId") { put("enum", JsonArray(options.map { JsonPrimitive(it.id) })) } }
            },
        )
        is PendingInteraction.OpenLink -> Interrupt(id.value, "kinuvo:open_link", message, metadata = kinuvo { put("site", site); put("url", url) })
        is PendingInteraction.Unsupported -> Interrupt(id.value, "kinuvo:unsupported", message)
        is PendingInteraction.Connect -> Interrupt(
            id.value, "kinuvo:connect", reason ?: "Connect $server", toolCallId.value,
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("connected") { put("type", "boolean") }
                    putJsonObject("error") { put("type", "string") }
                }
            },
            kinuvo {
                put("server", server)
                put("agentId", agentId.value)
                site?.let { put("site", it) }
                scope?.let { put("scope", ProtocolJson.encodeToJsonElement(it)) }
            },
        )
    }
}

private val approvalSchema = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { add(JsonPrimitive("decision")) }
    putJsonObject("properties") {
        putJsonObject("decision") { put("enum", JsonArray(listOf("allow_once", "allow_session", "always_allow", "deny").map(::JsonPrimitive))) }
        putJsonObject("note") { put("type", "string") }
    }
}

private fun questionsSchema(questions: PendingInteraction.Questions) = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { questions.questions.filter { it.required }.forEach { add(JsonPrimitive(it.id)) } }
    putJsonObject("properties") {
        questions.questions.forEach { q ->
            putJsonObject(q.id) {
                put("title", q.label)
                if (q.kind == QuestionKind.Multi) {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string"); if (q.options.isNotEmpty()) put("enum", JsonArray(q.options.map(::JsonPrimitive))) }
                } else {
                    put("type", if (q.kind == QuestionKind.Number) "number" else if (q.kind == QuestionKind.YesNo) "boolean" else "string")
                    if (q.kind.choice) put("enum", JsonArray(q.options.map(::JsonPrimitive)))
                }
            }
        }
    }
}
