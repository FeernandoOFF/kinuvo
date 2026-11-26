package kinuvo.protocol.session

import kinuvo.protocol.agent.AgentId
import kotlinx.serialization.Serializable

@Serializable
data class SessionParent(val agentId: AgentId, val sessionId: SessionId, val callId: ToolCallId, val depth: Int)

@Serializable
enum class SubAgentStatus { Running, Completed, Failed, Cancelled }

/** Every update identifies the child and the tool call that started its current turn. */
@Serializable
data class SubAgentState(
    val sessionId: SessionId,
    val agentId: AgentId,
    val callId: ToolCallId,
    val goal: String,
    val status: SubAgentStatus = SubAgentStatus.Running,
    val result: String? = null,
    val private: Boolean = true,
    val delivered: Boolean = false,
    val turnId: String? = null,
)

/** A terminal child turn announced by a Sub-agent ToolReport. Result text is in the report content. */
@Serializable
data class SubAgentCompletion(
    val sessionId: SessionId,
    val agentId: AgentId,
    val callId: ToolCallId,
    val name: String,
    val status: SubAgentStatus,
    val private: Boolean,
    val turnId: String? = null,
)
