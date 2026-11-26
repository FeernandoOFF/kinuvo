package kinuvo.providers.codex

import kotlinx.coroutines.flow.map

import kinuvo.protocol.usage.Usage
import kinuvo.harness.builtin.llModel

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
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.InternalAPI
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.url
import io.ktor.http.encodedPath
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.*
import kinuvo.protocol.model.ModelInfo

const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex/"

fun codexModel(id: String) = ModelInfo(id).llModel(LLMProvider.OpenAI, listOf(LLMCapability.OpenAIEndpoint.Responses))

private val debugLog = KotlinLogging.logger("codex")

/** Koog OpenAI client pointed at the ChatGPT Codex backend, authenticated with [CodexAuth]. */
fun codexLLMClient(auth: CodexAuth, usage: MutableStateFlow<Usage?> = MutableStateFlow(null)): OpenAILLMClient {
    val http = KoogHttpClient.fromKtorClient("codex", debugLog, codexHttpClient(auth::tokens, usage))
    return ResponsesLLMClient(
        settings = OpenAIClientSettings(baseUrl = CODEX_BASE_URL, responsesAPIPath = "responses"),
        httpClient = CodexMeasurements(http),
    )
}

/** Ktor client with Codex auth headers, one forced-refresh retry on 401, and usage capture. */
internal fun codexHttpClient(
    tokens: suspend (forceRefresh: Boolean) -> CodexTokens,
    usage: MutableStateFlow<Usage?>,
    engine: HttpClientEngine = SseContentTypeFix(NamespaceToolStreamFix(codexEngine())),
): HttpClient {
    suspend fun HttpRequestBuilder.authorize(forceRefresh: Boolean) {
        val tokens = tokens(forceRefresh)
        headers[HttpHeaders.Authorization] = "Bearer ${tokens.accessToken}"
        if (tokens.usesChatGptPlan) {
            val path = url.encodedPath.substringAfter("/codex/", url.encodedPath.removePrefix("/v1/"))
            url("https://api.openai.com/v1/$path")
            headers.remove("chatgpt-account-id")
            headers.remove("OpenAI-Beta")
            headers.remove("originator")
            require(path == "responses" || path == "models") { "ChatGPT plan usage supports models and Responses only" }
            if (path == "models") url.parameters.remove("client_version")
            (body as? OutgoingContent)?.forChatGptPlan()?.let { setBody(it) }
            return
        }
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
                (request.body as? OutgoingContent)?.withNonStrictTools()?.let { request.setBody(it) }
                request.authorize(forceRefresh = false)
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

internal fun OutgoingContent.forChatGptPlan(): OutgoingContent? = when (this) {
    is TextContent -> chatGptPlanRequest(text)?.let { TextContent(it, contentType) }
    is OutgoingContent.ContentWrapper -> delegate().forChatGptPlan()?.let { copy(it) }
    else -> null
}

internal fun chatGptPlanRequest(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val unsupported = setOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id")
    val result = root.filterKeys { it !in unsupported }.toMutableMap()
    result["store"] = JsonPrimitive(false)
    result["stream"] = JsonPrimitive(true)
    // Names grouped into the "kinuvo" namespace tool below; their echoed function_call items need the same
    // namespace so the API can match them back to it on the next turn.
    val namespacedNames = (root["tools"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.takeIf { t -> t["type"]?.jsonPrimitive?.contentOrNull in setOf("function", "custom") } }
        ?.mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
        ?.toSet() ?: emptySet()
    (root["input"] as? JsonArray)?.let { input ->
        result["input"] = JsonArray(input.map { item ->
            when {
                item !is JsonObject -> item
                item["role"] == JsonPrimitive("system") -> JsonObject(item + ("role" to JsonPrimitive("developer")))
                item["type"]?.jsonPrimitive?.contentOrNull in setOf("function_call", "custom_tool_call") &&
                    item["name"]?.jsonPrimitive?.contentOrNull in namespacedNames && "namespace" !in item ->
                    JsonObject(item + ("namespace" to JsonPrimitive("kinuvo")))
                else -> item
            }
        }.let(::withInterruptedOutputs))
    }
    (root["tools"] as? JsonArray)?.let { tools ->
        val supported = tools.filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content in setOf("function", "custom", "namespace") }
        val functions = supported.filter { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("function", "custom") }
        result["tools"] = JsonArray(supported.filter { it !in functions } + if (functions.isEmpty()) emptyList() else listOf(buildJsonObject {
            put("type", "namespace"); put("name", "kinuvo"); put("description", "Kinuvo tools"); put("tools", JsonArray(functions))
        }))
    }
    return JsonObject(result).toString()
}

/**
 * ChatGPT-plan SSE responses echo the request's `tools` back inside each event's `response`, including the
 * `namespace` wrapper [chatGptPlanRequest] sends. Koog's `OpenAIResponsesTool` has no `namespace` subtype, so the
 * stream must be flattened back to plain tools before koog parses it. Line-based so it never buffers a full
 * response, and tolerant of non-JSON / non-"data:" lines (comments, blank separators, "[DONE]").
 */
@Suppress("DEPRECATION")
@OptIn(InternalAPI::class)
internal class NamespaceToolStreamFix(private val delegate: HttpClientEngine) : HttpClientEngineBase("codex") {
    override val config = delegate.config
    override val supportedCapabilities = delegate.supportedCapabilities

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val res = delegate.execute(data)
        val body = res.body
        // Unlike the codex backend (see SseContentTypeFix), OpenAI's public API sets Content-Type correctly; that
        // also tells apart a streaming "responses" call from a plain JSON "models" call on the same host.
        if (data.url.host != "api.openai.com" || res.headers[HttpHeaders.ContentType]?.startsWith("text/event-stream") != true) return res
        // Real engines hand SSE requests back as a parsed session, so rewrite its events; raw bytes only in tests.
        if (body is io.ktor.client.plugins.sse.SSESession) {
            val session = object : io.ktor.client.plugins.sse.SSESession {
                override val coroutineContext = body.coroutineContext
                override val incoming = body.incoming.map { e ->
                    io.ktor.sse.ServerSentEvent(e.data?.let { flattenNamespaceTools(it) ?: it }, e.event, e.id, e.retry, e.comments)
                }
            }
            return HttpResponseData(res.statusCode, res.requestTime, res.headers, res.version, session, res.callContext)
        }
        if (body !is ByteReadChannel) return res
        val rewritten = CoroutineScope(res.callContext).writer(Dispatchers.Unconfined, autoFlush = true) {
            while (true) {
                val line = body.readUTF8Line(SSE_LINE_LIMIT) ?: break
                channel.writeStringUtf8(rewriteSseLine(line) + "\n")
            }
        }.channel
        return HttpResponseData(res.statusCode, res.requestTime, res.headers, res.version, rewritten, res.callContext)
    }

    override fun close() {
        super.close()
        delegate.close()
    }
}

private const val SSE_LINE_LIMIT = 16 * 1024 * 1024

internal fun rewriteSseLine(line: String): String {
    if (!line.startsWith("data:")) return line
    return flattenNamespaceTools(line.removePrefix("data:").trimStart())?.let { "data: $it" } ?: line
}

internal fun flattenNamespaceTools(json: String): String? {
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    val response = root["response"] as? JsonObject ?: return null
    val tools = response["tools"] as? JsonArray ?: return null
    if (tools.none { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "namespace" }) return null
    val flattened = tools.flatMap { t ->
        val obj = t as? JsonObject
        if (obj?.get("type")?.jsonPrimitive?.contentOrNull == "namespace") (obj["tools"] as? JsonArray) ?: JsonArray(emptyList())
        else listOf(t)
    }
    val newResponse = JsonObject(response + ("tools" to JsonArray(flattened)))
    return JsonObject(root + ("response" to newResponse)).toString()
}

private val ToolCallOutputs = mapOf("function_call" to "function_call_output", "custom_tool_call" to "custom_tool_call_output")

/**
 * A result for every tool call that has none, e.g. one cut off by closing the app. Plan requests resend the whole
 * history with `store: false`, and the API rejects a call without its output.
 */
internal fun withInterruptedOutputs(input: List<JsonElement>): List<JsonElement> {
    val answered = input.mapNotNullTo(mutableSetOf()) { item ->
        (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull in ToolCallOutputs.values }?.get("call_id")?.jsonPrimitive?.contentOrNull
    }
    return input.flatMap { item ->
        val call = item as? JsonObject
        val output = call?.get("type")?.jsonPrimitive?.contentOrNull?.let(ToolCallOutputs::get)
        val id = call?.get("call_id")?.jsonPrimitive?.contentOrNull
        if (output == null || id == null || id in answered) listOf(item)
        else listOf(item, buildJsonObject {
            put("type", output); put("call_id", id); put("output", "The tool call was interrupted before it returned a result.")
        })
    }
}
