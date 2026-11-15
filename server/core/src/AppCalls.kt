package kinuvo.server

import kinuvo.protocol.approval.InteractionId
import kinuvo.harness.Agent
import kinuvo.server.devices.*
import kinuvo.server.screens.*
import kinuvo.server.plugin.*

import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kinuvo.protocol.session.AgentEvent
import kotlinx.coroutines.NonCancellable
import kinuvo.protocol.session.SessionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.mcp.McpAppRef
import kinuvo.protocol.mcp.McpAppRequest
import kinuvo.protocol.mcp.McpAppResource
import kinuvo.protocol.mcp.McpAppToolRequest
import kinuvo.server.plugin.McpApps
import kinuvo.server.plugin.ToolRun
import kinuvo.harness.with
import kotlinx.serialization.json.*
import kinuvo.server.plugin.Tool
import kinuvo.server.tools.ToolService
import kinuvo.harness.ToolTurn
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId

/** Runs a tool of an MCP App's own server on the app's behalf, and shows only the Approval that needs. */
class AppCalls internal constructor(
    private val log: SessionLog,
    private val apps: () -> McpApps?,
    private val secrets: Secrets,
    private val trustRules: TrustRulesRepository,
    private val sessionTrust: SessionTrust,
    private val approvals: ApprovalBroker,
) {
    private val store get() = log
    /** The MCP App that [request]'s stored tool result shows; clients never name the app themselves. */
    private suspend fun app(request: McpAppRequest): Pair<McpApps, McpAppRef> {
        val ref = store.entries(request.agentId, request.sessionId).filterIsInstance<SessionEntry.ToolResult>()
            .lastOrNull { it.id == request.callId }?.app ?: error("No MCP App for this call")
        return (apps() ?: error("MCP Apps are unavailable")) to ref
    }

    suspend fun appResource(request: McpAppRequest): McpAppResource = app(request).let { (apps, ref) -> apps.resource(request.agentId, ref) }

    /** Runs a tool of the app's own MCP server for the app, through the tool service: the Session's Approvals, asked in its Session. */
    suspend fun appCallTool(agent: Agent, request: McpAppToolRequest): JsonObject {
        val (apps, ref) = app(request.app)
        val sessionId = request.app.sessionId
        val asked = secrets.redacting(apps.tool(agent.id, ref, request.name))
        // Checked again once the Approval is answered: the server or tool may have been removed meanwhile.
        val tool = object : Tool by asked {
            override suspend fun executeResult(args: JsonObject, callId: String, run: ToolRun) =
                secrets.redacting(apps.tool(agent.id, ref, request.name)).executeResult(args, callId, run)
        }
        val policy = ApprovalPolicy(agent.with(store.session(agent.id, sessionId).settings), trustRules, session = sessionTrust.scope(sessionId))
        val service = ToolService(listOf(tool), approvals = Approvals(policy, approvals, wait = APP_APPROVAL_WAIT))
        val call = SessionEntry.ToolCall(ToolCallId("app-${request.app.callId}-${Uuid.random()}"), tool.descriptor.name, request.arguments.toString(), tool.identity)
        val result = withAppApproval(agent, sessionId, request.name) { turn -> service.invoke(call, turn) }
        return result.appResult ?: buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", result.output) }) })
            put("isError", result.isError)
        }
    }

    /** Shows only the Approval a call needs, in its Session and one at a time: a Session shows a single pending Approval. */
    private suspend fun <T> withAppApproval(agent: Agent, sessionId: SessionId, name: String, call: suspend (ToolTurn) -> T): T {
        val lock = appApprovalLock(sessionId)
        var pending: InteractionId? = null
        val turn = ToolTurn(emit = { event ->
            when (event) {
                is AgentEvent.InteractionRequested -> {
                    lock.lock()
                    try {
                        check(log.status(sessionId) == SessionStatus.Idle) { "The Agent is busy; try '$name' again when it finishes" }
                        log.publish(agent.id, sessionId, event)
                        pending = event.interaction.id
                    } catch (e: Throwable) {
                        lock.unlock()
                        throw e
                    }
                }
                is AgentEvent.InteractionResolved -> if (pending != null) {
                    pending = null
                    try { log.publish(agent.id, sessionId, event) } finally { lock.unlock() }
                }
                else -> Unit
            }
        })
        try {
            return call(turn)
        } finally {
            pending?.let { id ->
                withContext(NonCancellable) {
                    try { log.publish(agent.id, sessionId, AgentEvent.InteractionResolved(id, false)) } finally { lock.unlock() }
                }
            }
        }
    }

    private val appApprovals = Mutex()
    private val appApprovalLocks = mutableMapOf<String, Mutex>()
    private suspend fun appApprovalLock(sessionId: SessionId) = appApprovals.withLock { appApprovalLocks.getOrPut(sessionId.value) { Mutex() } }

    private companion object { val APP_APPROVAL_WAIT = 10.minutes }
}
