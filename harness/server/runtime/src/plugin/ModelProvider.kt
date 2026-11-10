package kinuvo.harness.core.plugin

import kinuvo.protocol.PluginManifest
import kinuvo.protocol.PluginProviderDefinition
import kinuvo.protocol.PluginProviderKind
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.ProviderAdmin

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
