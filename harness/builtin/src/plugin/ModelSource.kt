package kinuvo.harness.builtin.plugin

import kinuvo.harness.AgentConfig
import kinuvo.harness.ModelPolicy
import kinuvo.harness.builtin.AgentModel
import kinuvo.protocol.model.ModelInfo

/** Builds the [AgentModel] an Agent's config asks for; [id] is what `provider:` names in agent.yaml. A host registers it with the built-in harness. */
interface ModelSource : ModelPolicy {
    fun model(config: AgentConfig): AgentModel
    fun model(config: AgentConfig, info: ModelInfo?): AgentModel = model(config)
}
