package kinuvo.harness.builtin.plugin

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.builtin.*
import kinuvo.harness.AgentConfig
import kinuvo.protocol.ModelInfo

/** Builds the [AgentModel] an Agent's config asks for; [id] is what `provider:` names in agent.yaml. */
interface ModelSource {
    val id: String
    fun model(config: AgentConfig): AgentModel
    fun model(config: AgentConfig, info: ModelInfo?): AgentModel = model(config)
    /** [model]'s [base] profile as this provider runs it, e.g. with a lower tool cap. */
    fun profile(model: String, base: ModelProfile): ModelProfile = base
    /** Whether [model] can be offered [tool], beyond what its profile allows. */
    fun accepts(model: String, tool: ToolDescriptor): Boolean = true
}
