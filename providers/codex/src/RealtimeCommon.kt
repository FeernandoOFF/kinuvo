package kinuvo.providers.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

internal fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()

// Match Codex's frameless context append limit, preserving code points and all text.
internal fun contextAppendChunks(text: String): List<String> {
    val bytes = text.encodeToByteArray()
    if (bytes.size <= 500) return listOf(text)
    return buildList {
        var start = 0
        while (start < bytes.size) {
            var end = minOf(start + 500, bytes.size)
            while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end--
            add(bytes.decodeToString(start, end))
            start = end
        }
    }
}

internal const val UNREACHABLE = "Couldn't reach ChatGPT. Check the Server's connection."
internal const val SIGN_IN = "Sign in to ChatGPT again in Settings → Model providers."
internal const val NEEDS_DEVICE_CODE = "Live voice needs ChatGPT signed in with a device code. Sign in that way in Settings → Model providers."
internal const val DEVICE_CODE_REASON = "Live voice needs ChatGPT signed in with a device code. Signing in that way replaces Continue with ChatGPT; chat keeps working."
internal const val ACCESS_REJECTED = "rejected_by_access_enforcement"

private fun errorOf(body: String) = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()

/** Whether ChatGPT rejected the token's kind, which refreshing it won't change. */
internal fun accessRejected(body: String) = errorOf(body)?.string("type") == ACCESS_REJECTED

/** What a Device sees when creating the call fails with [status] and [body]. */
internal fun callFailure(status: Int, body: String, now: Instant = Clock.System.now()): String {
    val error = errorOf(body)
    return when (status) {
        401, 403 -> if (accessRejected(body)) NEEDS_DEVICE_CODE else SIGN_IN
        429 -> if (error?.string("type") == "usage_limit_reached") {
            val seconds = error["resets_in_seconds"]?.jsonPrimitive?.longOrNull
                ?: error["resets_at"]?.jsonPrimitive?.longOrNull?.let { it - now.epochSeconds }
            "ChatGPT's usage limit is reached." + (seconds?.takeIf { it > 0 }?.let { " It resets in ${duration(it)}." } ?: "")
        } else "ChatGPT is busy. Try again in a moment."
        else -> "Couldn't start the call (HTTP $status)."
    }
}

private fun duration(seconds: Long): String {
    val minutes = maxOf(1, (seconds + 30) / 60)
    fun unit(n: Long, name: String) = "$n $name" + if (n == 1L) "" else "s"
    return when {
        minutes < 60 -> unit(minutes, "minute")
        minutes % 60 == 0L -> unit(minutes / 60, "hour")
        else -> "${unit(minutes / 60, "hour")} ${unit(minutes % 60, "minute")}"
    }
}

internal fun realtimeErrorMessage(error: JsonObject): String = error.string("message").ifBlank { "The call ended unexpectedly." }

// Request validation rejects one event; session/auth failures and unknown errors remain fatal.
internal fun recoverableRealtimeError(error: JsonObject): Boolean =
    error.string("type") == "invalid_request_error" && error.string("code") !in setOf(
        "session_expired", "session_closed", "session_not_found", "invalid_session",
        "invalid_api_key", "invalid_authentication", "authentication_error", "token_expired",
    )
