package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.*
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Attachment
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One run's controlled execution path, shared by the chat strategy and Scripts. */
class ToolExecutor(
    tools: List<Tool>,
    advertisedTools: List<ToolDescriptor> = tools.map { it.descriptor },
    private val approvals: Approvals = Approvals.none(),
    private val questions: Broker<Map<String, List<String>>?>? = null,
    private val attach: ((String, ByteArray) -> Attachment)? = null,
    private val emit: suspend (AgentEvent) -> Unit = {},
) {
    private val toolsByName = tools.indexedTools()
    private val callable = (advertisedTools + tools.map { it.descriptor }).distinctBy { it.name }

    suspend fun call(call: SessionEntry.ToolCall, stopped: (String) -> Unit = {}): SessionEntry.ToolResult =
        invoke(call.copy(identity = toolsByName[call.name]?.identity), call.id, stopped)

    private suspend fun invoke(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit): SessionEntry.ToolResult {
        emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
        val result = perform(call, approvalId, stopped)
        emit(AgentEvent.ToolCallResult(call.id, result.output, result.isError, result.identity))
        return result
    }

    /**
     * Runs [call] once its Approval allows it. [approvalId] is the stored call the Approval shows on: [call]'s own, or
     * the Script's for a call made from one. [stopped] is told what to record if the run is cancelled meanwhile.
     */
    private suspend fun perform(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit): SessionEntry.ToolResult {
        val tool = toolsByName[call.name]
        val verdict = tool?.let { approvals.policy?.decide(it, call.args) } ?: Verdict.Run
        val decision = when {
            verdict is Verdict.Block -> null
            verdict !is Verdict.Ask -> ApprovalDecision.AllowOnce
            approvals.unattended != null -> approvals.unattended
            else -> {
                stopped("Denied (stopped)")
                val pending = approvals.broker.register()
                try {
                    val a = verdict.assessment
                    val hint = when (tool?.kind) {
                        ToolKind.Shell -> verdict.trustKeys.joinToString(", ")
                        ToolKind.ComputerAction -> verdict.trustKeys.joinToString(", ") { it.removePrefix("${tool.policyKey} ") }
                        else -> ""
                    }.ifEmpty { null }
                    emit(AgentEvent.ApprovalRequested(pending.id, approvalId, call.name, call.args, hint, a.risk, a.reason, verdict.trustKeys.isNotEmpty(), tool?.identity))
                    val wait = approvals.wait
                    val answer = if (wait == null) pending.await() else withTimeoutOrNull(wait) { pending.await() }
                    stopped("Cancelled by user")
                    emit(AgentEvent.ApprovalResolved(approvalId, answer != null && answer !is ApprovalDecision.Deny))
                    if (answer == null) {
                        approvals.skipped(tool?.displayName ?: call.name)
                        return SessionEntry.ToolResult(call.id, call.name, "No answer within ${wait?.inWholeMinutes} minutes; skipped", isError = true, identity = tool?.identity)
                    }
                    answer
                } finally {
                    pending.close()
                }
            }
        }
        return when (decision) {
            null -> SessionEntry.ToolResult(call.id, call.name, (verdict as Verdict.Block).reason + " Don't retry it; find another way or ask the user.", isError = true)
            is ApprovalDecision.Deny -> SessionEntry.ToolResult(
                id = call.id, name = call.name,
                output = "The user denied this call." + (decision.note?.takeIf { it.isNotBlank() }?.let { " Note: $it" } ?: ""),
                isError = true,
            )
            else -> {
                if (decision == ApprovalDecision.AlwaysAllow && verdict is Verdict.Ask && tool != null) approvals.policy?.trust(tool, verdict.trustKeys)
                if (tool?.kind == ToolKind.AskUser) ask(call, approvalId) else execute(call, approvalId, stopped)
            }
        }.copy(identity = tool?.identity)
    }

    /** What [call]'s tool may call: the run's other tools, each shown as its own call and answered like one. */
    private fun toolRun(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit,
                                                  attached: MutableList<Attachment>) = object : ToolRun {
        private var count = 0
        override val tools = callable.filter { it.name != call.name }
        override suspend fun call(name: String, args: String): SessionEntry.ToolResult {
            val inner = SessionEntry.ToolCall("${call.id}-${++count}", name, args, toolsByName[name]?.identity)
            if (tools.none { it.name == name }) return SessionEntry.ToolResult(inner.id, name, "Unknown tool '$name'", isError = true)
            return invoke(inner, approvalId, stopped).also { attached += it.attachments }
        }
        override suspend fun attach(name: String, bytes: ByteArray) =
            requireNotNull(attach) { "Files can't be shown to the model in this run" }(name, bytes).also { attached += it }
    }

    private suspend fun ask(call: SessionEntry.ToolCall, formId: String = call.id): SessionEntry.ToolResult {
        val broker = questions ?: return SessionEntry.ToolResult(call.id, call.name, "There's no user to ask in this run.", isError = true)
        val (title, list) = try {
            val args = Json.parseToJsonElement(call.args.ifBlank { "{}" }).jsonObject
            args.string("title")?.ifBlank { null } to AskUserTool.questions(args)
        } catch (e: Exception) {
            return SessionEntry.ToolResult(call.id, call.name, "Error: ${e.message}", isError = true)
        }
        val pending = broker.register()
        try {
            emit(AgentEvent.QuestionsAsked(pending.id, formId, title, list))
            val answers = pending.await()
            emit(AgentEvent.QuestionsAnswered(formId))
            return SessionEntry.ToolResult(call.id, call.name, AskUserTool.result(list, answers))
        } finally {
            pending.close()
        }
    }

    private suspend fun execute(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit): SessionEntry.ToolResult {
        val tool = toolsByName[call.name]
            ?: return SessionEntry.ToolResult(call.id, call.name, "Unknown tool '${call.name}'", isError = true)
        val attached = mutableListOf<Attachment>()
        return try {
            val args = Json.parseToJsonElement(call.args.ifBlank { "{}" }).jsonObject
            val output = tool.execute(args, call.id, toolRun(call, approvalId, stopped, attached))
            SessionEntry.ToolResult(call.id, call.name, output, attachments = attached)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionEntry.ToolResult(call.id, call.name, "Error: ${e.message}", isError = true, attachments = attached)
        }
    }
}
