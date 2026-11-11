package kinuvo.server.plugin

import kinuvo.protocol.ModelInfo
import kinuvo.protocol.PluginManifest
import kinuvo.protocol.PluginProviderDefinition
import kinuvo.protocol.PluginProviderKind
import kotlinx.serialization.Serializable

/** A plugin that brings the admin half of model providers; hosts pair each with the built-in harness's source of the same ID. */
interface ProviderPlugin : HarnessPlugin {
    val providerAdmins: List<ProviderAdmin>

    override val manifest: PluginManifest get() = super.manifest.let { base ->
        base.copy(providers = providerAdmins.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.Model) } + base.providers)
    }
}

/** The custom model providers saved in the Server's config. */
interface CustomProviderStore {
    fun customProviders(): Map<String, ModelProviderConfig>
    fun saveCustomProvider(id: String, options: ModelProviderConfig)
}

@Serializable
data class ModelProviderConfig(
    val name: String? = null,
    val baseUrl: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val apiKeyEnv: String? = null,
    val apiKeyRequired: Boolean = true,
)
