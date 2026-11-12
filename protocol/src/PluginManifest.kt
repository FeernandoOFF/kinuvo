package kinuvo.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PluginManifest(
    val plugin: PluginDefinition,
    val formatVersion: Int = 1,
    val tools: List<PluginToolDefinition> = emptyList(),
    val discoversTools: Boolean = false,
    val providers: List<PluginProviderDefinition> = emptyList(),
    val access: PluginAccess = PluginAccess(),
) {
    fun checkSupported() {
        require(formatVersion == 1) { "Unsupported plugin manifest version: $formatVersion" }
        require(tools.all { it.id.isNotBlank() } && tools.map { it.id }.distinct().size == tools.size) { "Duplicate or empty tool IDs" }
        require(providers.all { provider ->
            val fields = provider.settings.fields
            provider.id.isNotBlank() && provider.settings.supported && fields.all { it.key.isNotBlank() } && fields.map { it.key }.distinct().size == fields.size
        } &&
            providers.map { it.kind to it.id }.distinct().size == providers.size) { "Invalid provider declarations" }
        require((access.network + access.files.keys + access.secrets).all { it.isNotBlank() }) { "Access needs a resource name" }
    }
}

@Serializable
data class PluginToolDefinition(val id: String, val description: String, val inputSchema: JsonObject, val risk: Risk = Risk.Medium)

@Serializable
enum class PluginProviderKind { Model, Tasks, Knowledge, WebSearch, Notifications }

@Serializable
data class PluginProviderDefinition(val id: String, val name: String, val kind: PluginProviderKind, val settings: SettingsSchema = SettingsSchema())

/** Resource names a plugin may request. The host supplies destinations, roots and bound values separately. */
@Serializable
data class PluginAccess(
    val network: Set<String> = emptySet(),
    val files: Map<String, PluginFileAccess> = emptyMap(),
    val secrets: Set<String> = emptySet(),
)

@Serializable
enum class PluginFileAccess { Read, ReadWrite }

@Serializable
data class PluginHttpRequest(
    val network: String,
    val path: List<String> = emptyList(),
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
)

@Serializable
data class PluginHttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: String)
