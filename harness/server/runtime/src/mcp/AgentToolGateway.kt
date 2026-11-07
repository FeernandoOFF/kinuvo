package kinuvo.harness.server.mcp

import kinuvo.harness.core.Verdict
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.server.HarnessRuntime
import kinuvo.protocol.ApprovalDecision
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class ToolReply(val text: String, val isError: Boolean = false)

/**
 * Runs Kinuvo tools for callers outside a run, such as External Agents over MCP, under the same Risk, Trust and Deny
 * rules as any tool call. [ask] puts an Approval in front of the user in the caller's Session.
 */
class AgentToolGateway(
    private val harness: HarnessRuntime,
    private val ask: suspend (CallerScope, Tool, JsonObject, Verdict.Ask) -> ApprovalDecision,
) {
    fun tools(scope: CallerScope): List<Tool> = harness.agentTools(scope)

    suspend fun call(scope: CallerScope, name: String, args: JsonObject): ToolReply {
        val tool = tools(scope).firstOrNull { it.descriptor.name == name } ?: return ToolReply("Unknown tool '$name'", true)
        return try {
            val policy = harness.approvalPolicy(scope)
            when (val verdict = policy.decide(tool, args.toString())) {
                Verdict.Run -> {}
                is Verdict.Block -> return ToolReply("${verdict.reason} Don't retry it; find another way or ask the user.", true)
                is Verdict.Ask -> when (val decision = ask(scope, tool, args, verdict)) {
                    is ApprovalDecision.Deny -> return ToolReply("The user denied this call." + (decision.note?.takeIf { it.isNotBlank() }?.let { " Note: $it" } ?: ""), true)
                    ApprovalDecision.AlwaysAllow -> policy.trust(tool, verdict.trustKeys)
                    ApprovalDecision.AllowForSession -> policy.trustForSession(tool, verdict.trustKeys)
                    ApprovalDecision.AllowOnce -> {}
                }
            }
            ToolReply(tool.execute(args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolReply("Error: ${e.message}", true)
        }
    }
}
