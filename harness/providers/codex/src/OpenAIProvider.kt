package kinuvo.harness.codex

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.readBuffer
import kinuvo.harness.core.*
import kinuvo.harness.core.store.AgentFiles
import kinuvo.protocol.Attachment
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ModelInfo
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

/** OpenAI API access shares the Responses adapter with the ChatGPT subscription provider. */
class OpenAIProvider(
    private val keyValue: suspend () -> String?,
    private val attachments: ((Attachment) -> ByteArray)? = null,
    private val http: HttpClient = openAIHttpClient(keyValue),
    override val apiKey: ProviderApiKey? = null,
) : ModelProvider, HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.openai", "OpenAI API")
    constructor(files: AgentFiles, secrets: Secrets = Secrets(), attachments: ((Attachment) -> ByteArray)? = null) :
        this(OpenAIKey(files, secrets), attachments)

    private constructor(key: StoredProviderKey, attachments: ((Attachment) -> ByteArray)?) :
        this(key::resolve, attachments, apiKey = key)

    val promptExecutor get() = executor.value
    override val id = "openai"
    override val name = "OpenAI API"
    override val catalogId = "openai"
    override val modelProviders get() = listOf(this)
    private val executor = lazy {
        koogExecutor(ResponsesLLMClient(
            settings = OpenAIClientSettings(baseUrl = OPENAI_BASE_URL, responsesAPIPath = "responses"),
            httpClient = CodexMeasurements(KoogHttpClient.fromKtorClient("openai", KotlinLogging.logger("openai"), http)),
        ))
    }

    override suspend fun availability(model: String) = if (keyValue().isNullOrBlank())
        CapabilityState.unavailable("Set an API key in Settings → Model providers, or OPENAI_API_KEY on the Server", "needs_api_key") else CapabilityState()

    override fun fileTypes(model: ModelInfo?) = responsesFileTypes(model)

    override suspend fun models(): List<ModelInfo> {
        val response = http.get("models")
        check(response.status.isSuccess()) { "OpenAI model discovery failed (${response.status.value})" }
        val bytes = response.bodyAsChannel().readBuffer(1024 * 1024L + 1).readByteArray()
        require(bytes.size <= 1024 * 1024) { "OpenAI model catalog is too large" }
        return parseOpenAIModels(bytes.decodeToString())
    }

    override fun model(config: AgentConfig): AgentModel = model(config, null)
    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel = openAIModel(config, executor.value, info, attachments)
    override suspend fun close() { if (executor.isInitialized()) executor.value.close(); http.close() }
}

internal const val OPENAI_BASE_URL = "https://api.openai.com/v1/"

internal fun openAIHttpClient(apiKey: suspend () -> String?, engine: HttpClientEngine = codexEngine()) = HttpClient(engine) {
    defaultRequest { url(OPENAI_BASE_URL) }
    install(SSE)
    install(HttpTimeout) { requestTimeoutMillis = 15 * 60_000L; socketTimeoutMillis = 5 * 60_000L }
    install(createClientPlugin("OpenAIAuth") {
        on(Send) { request ->
            request.bearerAuth(requireNotNull(apiKey()?.takeIf { it.isNotBlank() }) { "Set OPENAI_API_KEY on the Server" })
            (request.body as? OutgoingContent)?.withNonStrictTools()?.let { request.setBody(it) }
            proceed(request)
        }
    })
}

internal fun parseOpenAIModels(text: String): List<ModelInfo> = Json.parseToJsonElement(text).jsonObject.getValue("data").jsonArray
    .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    .filter { (it.startsWith("gpt-") || Regex("o[1-9].*").matches(it)) &&
        listOf("audio", "realtime", "transcribe", "tts", "image", "search", "instruct", "deep-research").none(it::contains) }
    .distinct().sorted().map { ModelInfo(it) }
