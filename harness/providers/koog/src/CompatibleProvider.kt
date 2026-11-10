package kinuvo.harness.koog

import kinuvo.harness.execution.api.AgentConfig
import kinuvo.server.plugin.CUSTOM_PROVIDERS
import kinuvo.server.plugin.CustomEndpoints
import kinuvo.server.plugin.ImageGeneration
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.ProviderApiKey
import kinuvo.harness.core.plugin.*
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.Prompt
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.protocol.Attachment
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ModelInfo
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import kinuvo.protocol.PluginDefinition
import kinuvo.protocol.CustomEndpointParams
import kinuvo.protocol.ProviderRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun compatibleProviders(files: AgentFiles, secrets: Secrets = Secrets(), attachments: ((Attachment) -> ByteArray)? = null): List<ModelProviderPlugin> {
    val configured = files.config().providers
    configured.forEach { (id, options) ->
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid model provider ID: $id" }
        endpoint(options.baseUrl.orEmpty())
    }
    return listOf(CompatibleEndpoints(files, secrets, attachments))
}

/** A custom provider's key, apart from built-in providers' keys; one saved under its bare ID before is moved on first read. */
private fun customKey(configId: String, options: ModelProviderConfig, files: AgentFiles, secrets: Secrets): StoredProviderKey {
    fun store(account: String) = Keychain.system("kinuvo", account) ?: PrivateFile(Path(files.home, "credentials", "$account-key"))
    fun reference(account: String) = PrivateFile(Path(files.home, "credentials", "$account-reference"))
    val account = "custom-$configId"
    // Other parts of Kinuvo keep their own secrets under these names; `openai`'s key is shared with the built-in provider, so it's copied.
    val legacy = configId !in setOf("codex", "protonpass", "mcp-oauth")
    val shared = configId == "openai"
    return StoredProviderKey(account, options.name ?: configId, options.apiKeyEnv ?: "OPENAI_COMPATIBLE_API_KEY", files, secrets,
        MovedKeychain(store(account), store(configId).takeIf { legacy }, shared), MovedKeychain(reference(account), reference(configId).takeIf { legacy }, shared))
}

private class MovedKeychain(private val current: Keychain, private val legacy: Keychain?, private val shared: Boolean) : Keychain {
    override val name get() = current.name
    override suspend fun read() = current.read()?.takeIf { it.isNotBlank() }
        ?: legacy?.read()?.takeIf { it.isNotBlank() }?.also { if (current.read().isNullOrBlank()) current.write(it); if (!shared) legacy.delete() }
    override suspend fun write(token: String) = current.write(token)
    override suspend fun delete() { current.delete(); if (!shared) legacy?.delete() }
}

internal class CompatibleProvider(
    private val configId: String,
    private val options: ModelProviderConfig,
    files: AgentFiles,
    secrets: Secrets,
    private val attachments: ((Attachment) -> ByteArray)?,
    engine: HttpClientEngine = providerEngine(),
    private val key: StoredProviderKey = customKey(configId, options, files, secrets),
) : ModelProvider, ModelProviderPlugin {
    override val images: ImageGeneration get() = CompatibleImages(http)
    override val id = CUSTOM_PROVIDERS + configId
    override val name = options.name ?: configId
    override val apiKey: ProviderApiKey? = if (options.apiKeyRequired) key else null
    override val definition = PluginDefinition("kinuvo.provider.$configId", name)
    override val modelProviders get() = listOf(this)
    private val http = providerHttpClient(endpoint(options.baseUrl.orEmpty()), key::resolve, options.apiKeyRequired, engine)
    private val client = CompatibleClient(KoogHttpClient.fromKtorClient(configId, KotlinLogging.logger(configId), http))
    private val executor = koogExecutor(client)

    override suspend fun availability(model: String) = if (options.apiKeyRequired && !key.status().configured)
        CapabilityState.unavailable("Set an API key in Settings → Model providers, or ${options.apiKeyEnv ?: "OPENAI_COMPATIBLE_API_KEY"} on the Server", "needs_api_key") else CapabilityState()

    override suspend fun models(): List<ModelInfo> {
        if (options.models.isNotEmpty()) return options.models
        return discoverCompatibleModels(http)
    }

    override fun fileTypes(model: ModelInfo?): Set<String> = emptySet()
    override fun model(config: AgentConfig) = model(config, null)
    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
        val metadata = info ?: options.models.find { it.id == config.model } ?: ModelInfo(config.model)
        return AgentModel(executor, metadata.llModel(LLMProvider.OpenAI, listOf(LLMCapability.OpenAIEndpoint.Completions)),
            attachments = attachments, replayReasoning = false)
    }

    override suspend fun close() { executor.close(); http.close() }
}

internal fun endpoint(value: String): String {
    require(value.isNotBlank()) { "Set baseUrl for the OpenAI-compatible provider" }
    val url = Url(value)
    require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) { "Model provider baseUrl must use HTTP or HTTPS" }
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Model provider baseUrl cannot contain credentials, a query, or a fragment" }
    return value.trimEnd('/') + "/"
}

internal fun providerHttpClient(baseUrl: String, key: suspend () -> String?, required: Boolean, engine: HttpClientEngine) = HttpClient(engine) {
    defaultRequest { url(baseUrl); contentType(ContentType.Application.Json) }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    install(SSE)
    install(HttpTimeout) { requestTimeoutMillis = 15 * 60_000L; socketTimeoutMillis = 5 * 60_000L }
    install(createClientPlugin("ProviderAuth") {
        on(Send) { request ->
            val token = key()?.takeIf { it.isNotBlank() }
            check(!required || token != null) { "Set an API key for this model provider on the Server" }
            if (token != null) request.bearerAuth(token)
            proceed(request)
        }
    })
}

internal expect fun providerEngine(): HttpClientEngine

private class CompatibleClient(http: KoogHttpClient) : OpenAILLMClient(
    httpClient = http,
    settings = OpenAIClientSettings(chatCompletionsPath = "chat/completions", modelsPath = "models"),
    toolsConverter = RawSchemaAwareGenerator(),
) {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        super.execute(prompt.groupAssistants(), model, tools)
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        super.executeStreaming(prompt.groupAssistants(), model, tools)
}

private fun Prompt.groupAssistants() = withMessages { messages -> buildList {
    messages.forEach { message ->
        val previous = lastOrNull()
        if (previous is Message.Assistant && message is Message.Assistant) {
            removeAt(lastIndex)
            add(previous.copy(parts = previous.parts + message.parts))
        } else add(message)
    }
} }

private suspend fun discoverCompatibleModels(http: HttpClient): List<ModelInfo> {
    val response = http.get("models")
    check(response.status.isSuccess()) { "Model discovery failed (${response.status.value})" }
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonArray
        .map { val m = it.jsonObject; val architecture = m["architecture"] as? JsonObject
            ModelInfo(m.getValue("id").jsonPrimitive.content,
                input = (architecture?.get("input_modalities") as? JsonArray)?.map { it.jsonPrimitive.content },
                output = (architecture?.get("output_modalities") as? JsonArray)?.map { it.jsonPrimitive.content }) }.distinctBy { it.id }.sortedBy { it.id }
}

private class CompatibleEndpoints(private val files: AgentFiles, private val secrets: Secrets,
    private val attachments: ((Attachment) -> ByteArray)?) : ModelProviderPlugin, CustomEndpoints {
    override val definition = PluginDefinition("kinuvo.custom-endpoints", "Custom providers")
    override val customEndpoints get() = this
    private val lock = Mutex()
    override val modelProviders get() = installed.value
    private val installed = MutableStateFlow(files.config().providers.map { (id, options) -> CompatibleProvider(id, options, files, secrets, attachments) })

    override suspend fun discover(params: CustomEndpointParams): List<ModelInfo> {
        val http = providerHttpClient(endpoint(params.baseUrl), { params.apiKey.takeIf { it.isNotBlank() } }, false, providerEngine())
        return try { withTimeoutOrNull(15_000) { discoverCompatibleModels(http) } ?: error("Model discovery timed out") } finally { http.close() }
    }

    override suspend fun save(params: CustomEndpointParams): ProviderRef = lock.withLock {
        require(params.id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Use a lowercase provider ID, letters, numbers and hyphens." }
        require(files.config().providers[params.id] == null) { "That provider ID is already in use." }
        val options = ModelProviderConfig(name = params.id, baseUrl = endpoint(params.baseUrl), apiKeyRequired = params.apiKey.isNotBlank(),
            models = params.model.takeIf { it.isNotBlank() }?.let { listOf(ModelInfo(it)) }.orEmpty())
        val provider = CompatibleProvider(params.id, options, files, secrets, attachments)
        try {
            if (params.apiKey.isNotBlank()) provider.apiKey?.save(params.apiKey)
            files.updateConfig { it.copy(providers = it.providers + (params.id to options)) }
            installed.value += provider
        } catch (e: Throwable) { provider.close(); throw e }
        ProviderRef(provider.id)
    }

    override suspend fun close() { installed.value.forEach { it.close() } }
}
