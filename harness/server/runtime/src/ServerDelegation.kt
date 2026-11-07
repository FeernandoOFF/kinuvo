package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.harness.core.agenttools.AgentDelegation
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.agenttools.Child
import kinuvo.harness.core.agenttools.ChildResult
import kinuvo.harness.core.agenttools.DelegateTask
import kinuvo.harness.server.execution.ExecutionCoordinator
import kinuvo.protocol.ChangePreview
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.ExecutionPhase
import kinuvo.protocol.ExternalApprovalOption
import kinuvo.protocol.Risk
import kinuvo.protocol.SendParams
import kinuvo.protocol.SendResult
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionParent
import kinuvo.protocol.SessionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Delegations as Sessions on the target Agent, started through the same paths as the user's: native Runs, or the
 * External Agent's execution. The child's first message is all it receives from the parent.
 */
internal class ServerDelegation(
    private val harness: HarnessRuntime,
    private val runs: Runs,
    private val external: ExecutionCoordinator,
    private val isExternal: suspend (agentId: String) -> Boolean,
    private val agent: suspend (agentId: String) -> Agent,
    private val create: suspend (agentId: String) -> SessionInfo,
    private val send: suspend (SendParams) -> SendResult,
    private val stopSession: suspend (sessionId: String) -> Unit,
) : AgentDelegation {
    override suspend fun delegate(parent: CallerScope, task: DelegateTask): Child {
        val parentId = requireNotNull(parent.sessionId)
        val viaExternal = isExternal(task.agentId)
        // Work handed on by a hidden Session stays hidden.
        val hidden = task.hidden || harness.store.session(parent.agentId, parentId).hidden
        require(!viaExternal || task.model == null) { "${task.agentId} is an External Agent: it chooses its own model" }
        if (hidden) {
            require(!viaExternal) { "${task.agentId} is an External Agent, which chooses its own model, so it can't run a hidden Session" }
            val config = agent(task.agentId).config
            val model = task.model ?: config.model
            require(harness.onDevice(config.provider, model)) { "A hidden Session needs an on-device model, but ${task.agentId} runs ${config.provider}/$model" }
        }
        val info = create(task.agentId)
        try {
            harness.log.append(task.agentId, info.id, SessionEntry.Details(hidden = hidden, parent = SessionParent(parent.agentId, parentId, parent.depth + 1, task.callId)))
            // A hidden Session keeps the on-device model it was started with, whatever its Agent's default becomes.
            val settings = if (hidden) agent(task.agentId).config.let { SessionSettings(provider = it.provider, model = task.model ?: it.model) } else task.model?.let { SessionSettings(model = it) }
            settings?.let { harness.configure(agent(task.agentId), info.id, it) }
            send(SendParams(task.agentId, info.id, task.message))
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { harness.log.deleteSession(task.agentId, info.id) } }
            throw e
        }
        return Child(task.agentId, info.id)
    }

    override suspend fun outcome(child: Child): ChildResult {
        if (external.owns(child.sessionId)) {
            while (external.snapshot(child.sessionId).phase.let { it == ExecutionPhase.Running || it == ExecutionPhase.RequiresAction }) delay(POLL_MS)
        } else runs.join(child.sessionId)
        (harness.log.completion(child.sessionId) as? AgentEvent.RunError)?.let { return ChildResult("Failed: ${it.message}", true) }
        val answer = harness.store.entries(child.agentId, child.sessionId).takeLastWhile { it !is SessionEntry.UserMessage }
            .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull { it.text.isNotBlank() }
        return ChildResult(answer?.text ?: "The Session ended without an answer.")
    }

    override suspend fun stop(sessionId: String) = stopSession(sessionId)

    override suspend fun review(parent: CallerScope, callId: String?, child: Child, result: String): Boolean {
        val parentId = requireNotNull(parent.sessionId)
        if (external.owns(parentId)) {
            val options = listOf(ExternalApprovalOption("allow", "Share it", "allow_once"), ExternalApprovalOption("deny", "Withhold it", "reject_once"))
            return external.ask(parentId, "Kinuvo: share a hidden result", result, options) == "allow"
        }
        val pending = harness.approvals.register()
        val toolCallId = callId ?: pending.id
        try {
            harness.log.publish(parent.agentId, parentId, AgentEvent.ApprovalRequested(pending.id, toolCallId, "delegate", buildJsonObject { put("result", result) }.toString(),
                risk = Risk.Medium, reason = "Review the result of a hidden Session before it reaches this conversation", trustable = false,
                preview = ChangePreview("Result of a hidden Session", body = result)))
            return pending.await().let { it !is ApprovalDecision.Deny }.also { harness.log.publish(parent.agentId, parentId, AgentEvent.ApprovalResolved(toolCallId, it)) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { harness.log.publish(parent.agentId, parentId, AgentEvent.ApprovalResolved(toolCallId, false)) }
            throw e
        } finally {
            pending.close()
        }
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
