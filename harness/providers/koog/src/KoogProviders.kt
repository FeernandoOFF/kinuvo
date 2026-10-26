package kinuvo.harness.koog

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.toLLModel
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
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
import io.ktor.client.request.setBody
import io.ktor.http.content.OutgoingContent
import io.ktor.http.ContentType
import io.ktor.client.statement.bodyAsText
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

internal data class ProviderKind(val id: String, val name: String, val url: String, val environmentKey: String, val catalog: String? = id)

internal val providerKinds = listOf(
    ProviderKind("anthropic", "Anthropic", "https://api.anthropic.com/", "ANTHROPIC_API_KEY"),
    ProviderKind("google", "Google", "https://generativelanguage.googleapis.com/", "GOOGLE_API_KEY"),
    ProviderKind("deepseek", "DeepSeek", "https://api.deepseek.com/", "DEEPSEEK_API_KEY"),
    ProviderKind("openrouter", "OpenRouter", "https://openrouter.ai/", "OPENROUTER_API_KEY"),
    ProviderKind("mistral", "Mistral", "https://api.mistral.ai/", "MISTRAL_API_KEY"),
    ProviderKind("alibaba", "Alibaba (DashScope)", "https://dashscope-intl.aliyuncs.com/", "DASHSCOPE_API_KEY"),
    ProviderKind("ollama", "Ollama", "http://localhost:11434/", "OLLAMA_API_KEY", null),
    ProviderKind("openai-compatible", "OpenAI compatible", "", "OPENAI_COMPATIBLE_API_KEY", null),
)

/** Endpoint changes take effect when the Server restarts; credentials resolve on every request. */
fun koogProviders(files: AgentFiles, secrets: Secrets = Secrets(), attachments: ((Attachment) -> ByteArray)? = null): List<HarnessPlugin> {
    val configured = files.config().providers
    val ids = (providerKinds.filter { it.id != "openai-compatible" }.map { it.id } + configured.keys).distinct()
        .filter { (configured[it]?.type ?: it) != "bedrock" }
    return ids.map { id ->
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid model provider ID: $id" }
        val options = configured[id] ?: ModelProviderConfig()
        val kind = requireNotNull(providerKinds.find { it.id == (options.type ?: id) }) { "Unknown Koog provider type for $id" }
        endpoint(options.baseUrl ?: kind.url)
        Triple(id, kind, options)
    }.map { (id, kind, options) -> KoogProvider(id, options.name ?: kind.name, kind, options, files, secrets, attachments) }
}

internal class KoogProvider(
    override val id: String,
    override val name: String,
    private val kind: ProviderKind,
    private val options: ModelProviderConfig,
    files: AgentFiles,
    secrets: Secrets,
    private val attachments: ((Attachment) -> ByteArray)?,
    engine: HttpClientEngine = providerEngine(),
    private val key: StoredProviderKey = StoredProviderKey(id, name, options.apiKeyEnv ?: kind.environmentKey, files, secrets),
) : ModelProvider, HarnessPlugin {
    private val url = endpoint(options.baseUrl ?: kind.url)
    private val needsKey = options.apiKeyRequired && kind.id != "ollama"
    override val apiKey: ProviderApiKey? = if (needsKey) key else null
    override val catalogId = kind.catalog
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.provider.$id", name)
    override val modelProviders get() = listOf(this)
    private val http = providerHttpClient(url, kind.id, key::resolve, needsKey, engine)
    private val client = lazy { createClient(KoogHttpClient.fromKtorClient(id, KotlinLogging.logger(id), http)) }
    private val executor = lazy { koogExecutor(if (kind.id in listOf("anthropic", "google")) SchemaClient(client.value) else client.value) }

    private fun createClient(http: KoogHttpClient): LLMClient = when (kind.id) {
        "anthropic" -> AnthropicClient(http)
        "google" -> GoogleClient(http)
        "deepseek" -> DeepSeekLLMClient(httpClient = http, toolsConverter = RawSchemaAwareGenerator())
        "openrouter" -> OpenRouterLLMClient(httpClient = http, toolsConverter = RawSchemaAwareGenerator())
        "mistral" -> MistralAILLMClient(httpClient = http, toolsConverter = RawSchemaAwareGenerator())
        "alibaba" -> DashscopeLLMClient(httpClient = http, toolsConverter = RawSchemaAwareGenerator())
        "ollama" -> OllamaClient(httpClient = http, toolDescriptorConverter = RawSchemaAwareGenerator())
        else -> OpenAILLMClient(httpClient = http, settings = OpenAIClientSettings(chatCompletionsPath = "chat/completions", modelsPath = "models"), toolsConverter = RawSchemaAwareGenerator())
    }

    override suspend fun availability(model: String) = if (needsKey && !key.status().configured)
        CapabilityState.unavailable("Set an API key in Settings → Model providers, or ${options.apiKeyEnv ?: kind.environmentKey} on the Server", "needs_api_key") else CapabilityState()

    override suspend fun models(): List<ModelInfo> {
        if (options.models.isNotEmpty()) return options.models
        return when (kind.id) {
            "ollama" -> (client.value as OllamaClient).getModels().map { it.toLLModel() }.chatModels().map { it.info() }
            "alibaba", "openai-compatible" -> {
                val response = http.get(if (kind.id == "alibaba") "compatible-mode/v1/models" else "models")
                check(response.status.isSuccess()) { "$name model discovery failed (${response.status.value})" }
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonArray
                    .map { ModelInfo(it.jsonObject.getValue("id").jsonPrimitive.content) }
            }
            "google" -> client.value.models().map { model ->
                val id = model.id.removePrefix("models/")
                GoogleModels.models.find { it.id == id } ?: model.copy(id = id)
            }.chatModels().map { it.info() }
            else -> client.value.models().chatModels().map { it.info() }
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    override fun fileTypes(model: ModelInfo?): Set<String> = when (kind.id) {
        "anthropic", "google" -> if ("file" in model?.input.orEmpty()) setOf("application/pdf") else emptySet()
        else -> emptySet()
    }

    override fun model(config: AgentConfig) = model(config, null)
    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
        val provider = client.value.llmProvider()
        val metadata = info ?: options.models.find { it.id == config.model } ?: ModelInfo(config.model)
        val native = metadata.llModel(provider, if (kind.id == "openai-compatible") listOf(LLMCapability.OpenAIEndpoint.Completions) else emptyList())
        return AgentModel(executor.value, native, attachments = attachments, reasoningInContent = kind.id != "openai-compatible", reasoningFormat = kind.id)
    }

    override suspend fun close() {
        if (executor.isInitialized()) executor.value.close() else if (client.isInitialized()) client.value.close()
        http.close()
    }
}

internal fun endpoint(value: String): String {
    require(value.isNotBlank()) { "Set baseUrl for the OpenAI-compatible provider" }
    val url = Url(value)
    require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) { "Model provider baseUrl must use HTTP or HTTPS" }
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Model provider baseUrl cannot contain credentials, a query, or a fragment" }
    return value.trimEnd('/') + "/"
}

internal fun providerHttpClient(baseUrl: String, kind: String, key: suspend () -> String?, required: Boolean, engine: HttpClientEngine) = HttpClient(engine) {
    defaultRequest { url(baseUrl); contentType(ContentType.Application.Json) }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    install(SSE)
    install(HttpTimeout) { requestTimeoutMillis = 15 * 60_000L; socketTimeoutMillis = 5 * 60_000L }
    install(createClientPlugin("ProviderAuth") {
        on(Send) { request ->
            val token = key()?.takeIf { it.isNotBlank() }
            check(!required || token != null) { "Set an API key for this model provider on the Server" }
            if (token != null) when (kind) {
                "anthropic" -> request.headers.append("x-api-key", token)
                "google" -> request.headers.append("x-goog-api-key", token)
                else -> request.bearerAuth(token)
            }
            if (kind == "anthropic") request.headers.append("anthropic-version", "2023-06-01")
            if (kind in listOf("anthropic", "google")) {
                (request.body as? OutgoingContent)?.let { request.setBody(it.withNativeSchemas(kind)) }
            }
            proceed(request)
        }
    })
}

internal fun List<LLModel>.chatModels() = filter { it.capabilities.isNullOrEmpty() || it.supports(LLMCapability.Completion) }

internal fun LLModel.info() = if (capabilities.isNullOrEmpty()) ModelInfo(id) else ModelInfo(id,
    tools = supports(LLMCapability.Tools),
    reasoning = if (supports(LLMCapability.Thinking)) null else emptyList(),
    input = buildList { add("text"); if (supports(LLMCapability.Vision.Image)) add("image"); if (supports(LLMCapability.Document)) add("file") },
    context = contextLength?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
)

internal expect fun providerEngine(): HttpClientEngine
