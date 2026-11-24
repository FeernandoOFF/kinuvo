package kinuvo.providers.codex

import ai.koog.http.client.KoogHttpClient
import kinuvo.harness.builtin.TokenUsageCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.reflect.KClass

/** Capture usage, skip Codex metadata events and flatten `namespace` tools unsupported by Koog; no response content is retained. */
internal class CodexMeasurements(private val client: KoogHttpClient) : KoogHttpClient by client {
    override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
        val capture = currentCoroutineContext()[TokenUsageCapture]
        emitAll(client.sse(path, requestBody, requestBodyType, { data -> dataFilter(data) && !isResponseMetadata(data) }, { data ->
            (chatGptPlanFailure(data) ?: streamError(data))?.let { throw IllegalStateException(it) }
            capture?.read(data)
            // Koog has no `namespace` tool type, which ChatGPT-plan events echo; any engine hands events through here.
            decodeStreamingResponse(flattenNamespaceTools(data) ?: data)
        }, processStreamingChunk, parameters, headers))
    }
}

private fun isResponseMetadata(data: String?): Boolean = data != null && runCatching {
    Json.parseToJsonElement(data).jsonObject["type"]?.jsonPrimitive?.content == "response.metadata"
}.getOrDefault(false)

internal fun TokenUsageCapture.read(data: String) {
    runCatching { readUsage(data) }
}

private fun TokenUsageCapture.readUsage(data: String) {
    val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
    if (event["type"]?.jsonPrimitive?.content !in listOf("response.completed", "response.incomplete")) return
    completed = event["type"]?.jsonPrimitive?.content == "response.completed"
    val usage = runCatching { event["response"]?.jsonObject?.get("usage")?.jsonObject }.getOrNull() ?: return
    fun value(key: String) = usage[key]?.jsonPrimitive?.intOrNull
    input = value("input_tokens")
    output = value("output_tokens")
    reasoning = runCatching { usage["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull }.getOrNull()
    val details = runCatching { usage["input_tokens_details"]?.jsonObject }.getOrNull()
    cached = details?.get("cached_tokens")?.jsonPrimitive?.intOrNull
    cacheWrite = details?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull
}

internal fun chatGptPlanFailure(data: String): String? {
    val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    if (event["type"]?.jsonPrimitive?.content != "response.failed") return null
    val error = runCatching { event["response"]?.jsonObject?.get("error")?.jsonObject }.getOrNull() ?: return null
    return when (error["code"]?.jsonPrimitive?.content) {
        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT plan usage limit reached. Check your app's limit at https://chatgpt.com/settings/usage and try again after it resets."
        "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is temporarily unavailable. Try again later; your sign-in has been retained."
        else -> null
    }
}

/**
 * A stream `error` event's message. Koog requires `message` and `sequence_number` on it, but ChatGPT-plan streams
 * can nest the details under `error` instead (seen after the app was backgrounded mid-run), which hid the real error.
 */
internal fun streamError(data: String): String? {
    val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    if (event["type"]?.jsonPrimitive?.content != "error") return null
    val nested = runCatching { event["error"]?.jsonObject }.getOrNull()
    fun field(key: String) = runCatching { (event[key] ?: nested?.get(key))?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
    val message = field("message")
    val code = field("code") ?: runCatching { nested?.get("type")?.jsonPrimitive?.content }.getOrNull()
    return listOfNotNull(message, code?.let { "($it)" }).joinToString(" ").ifBlank { "The model stream failed without details. Try again." }
}
