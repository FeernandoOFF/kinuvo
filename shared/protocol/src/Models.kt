package kinuvo.protocol

import kotlinx.serialization.Serializable

/** How capable a model is as an agent, which sets its prompt, tools and step limit. */
@Serializable
enum class ModelTier { NoTools, Micro, Small, Mid, Frontier }

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = id,
    val reasoning: List<String>? = null,
    val defaultReasoning: String? = null,
    val input: List<String>? = null,
    val output: List<String>? = null,
    val tools: Boolean? = null,
    val context: Int? = null,
    val cost: ModelCost? = null,
    /** Stable [ToolIdentity.key] values this model can call; null means unrestricted. */
    val toolIds: Set<String>? = null,
    val location: ExecutionLocation = ExecutionLocation.External,
    /** Set by the provider or the owner's config; the Server infers it when null. */
    val tier: ModelTier? = null,
    val state: CapabilityState = CapabilityState(),
)

@Serializable
data class ModelCost(val input: Double? = null, val output: Double? = null)

@Serializable
data class ProviderModels(
    val id: String,
    val name: String,
    val models: List<ModelInfo> = emptyList(),
    val stale: Boolean = false,
    val warning: String? = null,
)

@Serializable
data class ModelsCatalog(val providers: List<ProviderModels> = emptyList(), val warning: String? = null)

@Serializable
data class ModelsParams(val refresh: Boolean = false)

/** [label] names the signed-in account, e.g. its email. */
@Serializable
data class ProviderAccount(val signedIn: Boolean, val label: String? = null)

/** What to open and enter to sign in; [expiresAt] is epoch millis. */
@Serializable
data class DeviceCode(val url: String, val code: String, val expiresAt: Long)

/** A model provider on the Server; [account] describes browser sign-in, [apiKey] API-key configuration, [downloads] models it runs from downloaded files. */
@Serializable
data class ProviderInfo(val id: String, val name: String, val account: ProviderAccount? = null, val error: String? = null, val apiKey: ProviderApiKeyStatus? = null,
    val downloads: ProviderDownloads? = null)

/** A model a provider runs from files downloaded to the Server; [supported] is false when the Server's machine can't run it, saying why in [reason]. */
@Serializable
data class DownloadableModel(val id: String, val name: String, val bytes: Long, val memory: Long, val installed: Boolean = false,
    val supported: Boolean = true, val reason: String? = null, val license: String? = null, val source: String? = null)

/** The models a provider downloads, and the one [downloading] with its [downloaded] bytes. */
@Serializable
data class ProviderDownloads(val models: List<DownloadableModel> = emptyList(), val downloading: String? = null, val downloaded: Long = 0, val error: String? = null)

@Serializable
data class ProviderModelRef(val provider: String, val model: String)

@Serializable
data class ProviderRef(val provider: String)

/** Configuration status only; a saved key is never returned to clients. */
@Serializable
data class ProviderApiKeyStatus(val configured: Boolean, val storage: String, val saved: Boolean = false, val reference: String? = null)

@Serializable
data class SetProviderApiKeyParams(val provider: String, val value: String)
