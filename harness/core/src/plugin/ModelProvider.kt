package kinuvo.harness.core.plugin

import kinuvo.harness.core.*
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Prefix of the model providers the owner configures, so they never take a built-in provider's ID. */
const val CUSTOM_PROVIDERS = "custom/"

/** Builds the [AgentModel] an Agent's config asks for; [id] is what `provider:` names in agent.yaml. */
interface ModelProvider {
    val id: String
    val name: String get() = id
    /** Provider key in Models.dev, for optional metadata enrichment. */
    val catalogId: String? get() = null
    suspend fun models(): List<ModelInfo> = emptyList()
    suspend fun availability(model: String): CapabilityState = CapabilityState()
    /** Native file MIME types accepted by this endpoint, separately from model input modalities. */
    fun fileTypes(model: ModelInfo?): Set<String> = if (model?.input.orEmpty().any { it == "file" || it == "document" })
        ATTACHMENT_MIME_TYPES.filter { attachmentInput(it) == "file" }.toSet() else emptySet()
    fun model(config: AgentConfig): AgentModel
    fun model(config: AgentConfig, info: ModelInfo?): AgentModel = model(config)
    /** Plan usage, for providers that report it. */
    val usage: StateFlow<Usage?>? get() = null
    /** The [tools] [model] can take, e.g. fewer and simpler ones for a small model; the rest aren't offered. */
    fun usableTools(model: String, tools: List<ToolDescriptor>) = tools
    fun usableTools(model: String, tools: List<ToolDescriptor>, identities: Map<String, ToolIdentity>) = usableTools(model, tools)
    /** Whether [model] is too small for the full instructions and gets [basicContextFor] instead. */
    fun basicPrompt(model: String) = false
    /** Signs in to the provider's account; null when it needs no sign-in. */
    val auth: ProviderAuth? get() = null
    val apiKey: ProviderApiKey? get() = null
    val downloads: ModelDownloads? get() = null
    val images: ImageGeneration? get() = null
}

/** API keys or Secret references, configured on the Server without exposing saved values. */
interface ProviderApiKey {
    suspend fun status(): ProviderApiKeyStatus
    /** An empty [value] removes the saved setting. */
    suspend fun save(value: String)
}

/** Models a provider runs from files it downloads to the Server, one download at a time; [changes] follows [state]. */
interface ModelDownloads {
    val changes: Flow<ProviderDownloads>
    fun state(): ProviderDownloads
    fun download(model: String)
    fun cancel()
    suspend fun remove(model: String)
}

/** A model provider's account sign-in, done with a device code so it works from any client or a headless Server. */
interface ProviderAuth {
    suspend fun account(): ProviderAccount
    /** Signs in, handing the URL and code to enter to [show]; returns once signed in, or throws when it fails or expires. */
    suspend fun login(show: (DeviceCode) -> Unit)
    suspend fun logout()
}


data class GeneratedImage(val bytes: ByteArray, val mimeType: String = "image/png")

interface ImageGeneration {
    suspend fun generate(model: String, prompt: String, references: List<GeneratedImage> = emptyList(), size: String? = null): List<GeneratedImage>
}
