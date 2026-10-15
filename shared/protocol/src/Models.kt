package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = id,
    val reasoning: List<String>? = null,
    val defaultReasoning: String? = null,
    val input: List<String>? = null,
    val tools: Boolean? = null,
    val context: Int? = null,
    val cost: ModelCost? = null,
    /** Stable [ToolIdentity.key] values this model can call; null means unrestricted. */
    val toolIds: Set<String>? = null,
    val location: ExecutionLocation = ExecutionLocation.External,
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

/** A model provider on the Server; [account] describes browser sign-in, [apiKey] API-key configuration. */
@Serializable
data class ProviderInfo(val id: String, val name: String, val account: ProviderAccount? = null, val error: String? = null, val apiKey: ProviderApiKeyStatus? = null)

@Serializable
data class ProviderRef(val provider: String)

/** Configuration status only; a saved key is never returned to clients. */
@Serializable
data class ProviderApiKeyStatus(val configured: Boolean, val storage: String, val saved: Boolean = false, val reference: String? = null)

@Serializable
data class SetProviderApiKeyParams(val provider: String, val value: String)
