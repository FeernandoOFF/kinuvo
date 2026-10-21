package kinuvo.harness.core.knowledge

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentModel
import kinuvo.protocol.SessionEntry

/** The exact parent request prefix and model configuration; tool execution is supplied separately. */
data class ReflectionContext(
    val agent: Agent,
    val model: AgentModel,
    val instructions: String,
    val history: List<SessionEntry>,
    val tools: List<ToolDescriptor>,
)
