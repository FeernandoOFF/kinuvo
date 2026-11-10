package kinuvo.harness.core.plugin

import kinuvo.server.plugin.HarnessPlugin
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.*
import kinuvo.harness.execution.api.AgentConfig
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.PluginManifest
import kinuvo.protocol.PluginProviderDefinition
import kinuvo.protocol.PluginProviderKind
import kinuvo.server.plugin.ProviderAdmin

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

/** A model provider as a Server runs it: the models it builds, and what the Server manages of it. */
interface ModelProvider : ModelSource, ProviderAdmin {
    override val id: String
}

/** A plugin that brings model providers; hosts register them with the Server, outside the plugin SPI. */
interface ModelProviderPlugin : HarnessPlugin {
    val modelProviders: List<ModelProvider>

    override val manifest: PluginManifest get() = super.manifest.let { base ->
        base.copy(providers = modelProviders.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.Model) } + base.providers)
    }
}
