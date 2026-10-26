package kinuvo.harness.koog

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
import kotlinx.serialization.json.*

fun compatibleProviders(files: AgentFiles, secrets: Secrets = Secrets(), attachments: ((Attachment) -> ByteArray)? = null): List<HarnessPlugin> {
    val configured = files.config().providers
    configured.forEach { (id, options) ->
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid model provider ID: $id" }
        endpoint(options.baseUrl.orEmpty())
    }
    return configured.map { (id, options) -> CompatibleProvider(id, options, files, secrets, attachments) }
}

internal class CompatibleProvider(
    override val id: String,
    private val options: ModelProviderConfig,
    files: AgentFiles,
    secrets: Secrets,
    private val attachments: ((Attachment) -> ByteArray)?,
    engine: HttpClientEngine = providerEngine(),
    private val key: StoredProviderKey = StoredProviderKey(id, options.name ?: id, options.apiKeyEnv ?: "OPENAI_COMPATIBLE_API_KEY", files, secrets),
) : ModelProvider, HarnessPlugin {
    override val name = options.name ?: id
    override val apiKey: ProviderApiKey? = if (options.apiKeyRequired) key else null
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.provider.$id", name)
    override val modelProviders get() = listOf(this)
    private val http = providerHttpClient(endpoint(options.baseUrl.orEmpty()), key::resolve, options.apiKeyRequired, engine)
    private val client = CompatibleClient(KoogHttpClient.fromKtorClient(id, KotlinLogging.logger(id), http))
    private val executor = koogExecutor(client)

    override suspend fun availability(model: String) = if (options.apiKeyRequired && !key.status().configured)
        CapabilityState.unavailable("Set an API key in Settings → Model providers, or ${options.apiKeyEnv ?: "OPENAI_COMPATIBLE_API_KEY"} on the Server", "needs_api_key") else CapabilityState()

    override suspend fun models(): List<ModelInfo> {
        if (options.models.isNotEmpty()) return options.models
        val response = http.get("models")
        check(response.status.isSuccess()) { "$name model discovery failed (${response.status.value})" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonArray
            .map { ModelInfo(it.jsonObject.getValue("id").jsonPrimitive.content) }.distinctBy { it.id }.sortedBy { it.id }
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
