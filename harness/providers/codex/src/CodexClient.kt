package kinuvo.harness.codex

import kinuvo.protocol.Usage
import kinuvo.harness.core.llModel

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.fromKtorClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.isSseRequest
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.InternalAPI
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.setBody
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex/"

fun codexModel(id: String) = kinuvo.protocol.ModelInfo(id).llModel(LLMProvider.OpenAI, listOf(LLMCapability.OpenAIEndpoint.Responses))

private val debugLog = KotlinLogging.logger("codex")

/** Koog OpenAI client pointed at the ChatGPT Codex backend, authenticated with [CodexAuth]. */
fun codexLLMClient(auth: CodexAuth, usage: MutableStateFlow<Usage?> = MutableStateFlow(null)): OpenAILLMClient {
    val http = KoogHttpClient.fromKtorClient("codex", debugLog, codexHttpClient(auth::tokens, usage))
    return OpenAILLMClient(
        settings = OpenAIClientSettings(baseUrl = CODEX_BASE_URL, responsesAPIPath = "responses"),
        httpClient = CodexMeasurements(http),
        toolsConverter = RawSchemaAwareGenerator(),
    )
}

/** Ktor client with Codex auth headers, one forced-refresh retry on 401, and usage capture. */
internal fun codexHttpClient(
    tokens: suspend (forceRefresh: Boolean) -> CodexTokens,
    usage: MutableStateFlow<Usage?>,
    engine: HttpClientEngine = SseContentTypeFix(codexEngine()),
): HttpClient {
    suspend fun HttpRequestBuilder.authorize(forceRefresh: Boolean) {
        val tokens = tokens(forceRefresh)
        headers[HttpHeaders.Authorization] = "Bearer ${tokens.accessToken}"
        headers["chatgpt-account-id"] = tokens.accountId
        headers["OpenAI-Beta"] = "responses=experimental"
        headers["originator"] = "kinuvo"
    }
    return HttpClient(engine) {
        defaultRequest {
            url(CODEX_BASE_URL)
            contentType(ContentType.Application.Json)
        }
        install(SSE)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 15 * 60_000L; socketTimeoutMillis = 5 * 60_000L }
        // A plugin, not HttpSend.intercept: Koog re-creates the client via config(), which drops interceptors.
        install(createClientPlugin("CodexAuth") {
            on(Send) { request ->
                request.authorize(forceRefresh = false)
                (request.body as? OutgoingContent)?.withNonStrictTools()?.let { request.setBody(it) }
                debugLog.debug { "codex request: ${(request.body as? OutgoingContent)?.text() ?: request.body}" }
                var call = proceed(request)
                if (call.response.status == HttpStatusCode.Unauthorized) {
                    request.authorize(forceRefresh = true)
                    call = proceed(request)
                }
                codexUsageFrom(call.response.headers)?.let { usage.value = it }
                call
            }
        })
    }
}

/**
 * [body] with its function tools marked `strict: false`, as the Codex CLI sends them; null when nothing changes. The
 * Responses API defaults to strict, which makes every parameter required, so models fill optional ones with "".
 */
internal fun OutgoingContent.withNonStrictTools(): OutgoingContent? = when (this) {
    is TextContent -> nonStrictTools(text)?.let { TextContent(it, contentType) }
    // Streaming requests wrap the JSON in SSEClientContent.
    is OutgoingContent.ContentWrapper -> delegate().withNonStrictTools()?.let { copy(it) }
    else -> null
}

private fun OutgoingContent.text(): String? = when (this) {
    is TextContent -> text
    is OutgoingContent.ContentWrapper -> delegate().text()
    else -> null
}

internal fun nonStrictTools(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val tools = root["tools"] as? JsonArray ?: return null
    val fixed = tools.map { t ->
        if (t is JsonObject && (t["type"] as? JsonPrimitive)?.content == "function" && "strict" !in t) JsonObject(t + ("strict" to JsonPrimitive(false))) else t
    }
    return if (fixed == tools) null else JsonObject(root + ("tools" to JsonArray(fixed))).toString()
}

/** The Codex backend streams SSE without a Content-Type header, which Ktor's SSE plugin rejects. */
@Suppress("DEPRECATION")
@OptIn(InternalAPI::class)
private class SseContentTypeFix(private val delegate: HttpClientEngine) : HttpClientEngineBase("codex") {
    override val config = delegate.config
    override val supportedCapabilities = delegate.supportedCapabilities

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val res = delegate.execute(data)
        if (res.statusCode != HttpStatusCode.OK || res.headers.contains(HttpHeaders.ContentType)) return res
        val headers = Headers.build {
            appendAll(res.headers)
            append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        }
        val body = res.body
        val sseBody = if (data.isSseRequest() && body is ByteReadChannel) {
            DefaultClientSSESession(data.body as SSEClientContent, body)
        } else body
        return HttpResponseData(res.statusCode, res.requestTime, headers, res.version, sseBody, res.callContext)
    }

    override fun close() {
        super.close()
        delegate.close()
    }
}
