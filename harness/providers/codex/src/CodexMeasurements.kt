package kinuvo.harness.codex

import ai.koog.http.client.KoogHttpClient
import kinuvo.harness.core.TokenUsageCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.reflect.KClass

/** Read usage before Koog drops the cache fields; no request or response content is retained. */
internal class CodexMeasurements(private val client: KoogHttpClient) : KoogHttpClient by client {
    override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = flow {
        val capture = currentCoroutineContext()[TokenUsageCapture]
        emitAll(client.sse(path, requestBody, requestBodyType, dataFilter, { data ->
            capture?.read(data)
            decodeStreamingResponse(data)
        }, processStreamingChunk, parameters, headers))
    }
}

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
    val details = runCatching { usage["input_tokens_details"]?.jsonObject }.getOrNull()
    cached = details?.get("cached_tokens")?.jsonPrimitive?.intOrNull
    cacheWrite = details?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull
}
