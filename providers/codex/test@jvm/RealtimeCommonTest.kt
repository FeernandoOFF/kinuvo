package kinuvo.providers.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class RealtimeCommonTest {
    @Test
    fun emptyAndExactBoundary() {
        assertEquals(listOf(""), contextAppendChunks(""))
        assertEquals(listOf("a".repeat(500)), contextAppendChunks("a".repeat(500)))
        assertEquals(listOf("a".repeat(500), "b"), contextAppendChunks("a".repeat(500) + "b"))
    }

    @Test
    fun preservesMultibyteCharactersAtBoundary() {
        for (character in listOf("é", "界", "😀")) {
            for (prefix in 497..500) {
                val text = "a".repeat(prefix) + character.repeat(600)
                val chunks = contextAppendChunks(text)
                assertEquals(text, chunks.joinToString(""))
                assertTrue(chunks.all { it.encodeToByteArray().size <= 500 })
                assertTrue(chunks.none { '\uFFFD' in it })
            }
        }
    }

    @Test
    fun preservesLongAnswer() {
        val text = "A long answer with spaces. 界😀\n".repeat(10_000)
        val chunks = contextAppendChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.encodeToByteArray().size <= 500 })
    }

    @Test
    fun onlyRequestErrorsAreRecoverable() {
        fun recoverable(type: String, code: String) = recoverableRealtimeError(buildJsonObject {
            put("type", type)
            put("code", code)
            put("param", "content")
        })
        assertTrue(recoverable("invalid_request_error", "invalid_value"))
        assertTrue(recoverableRealtimeError(buildJsonObject { put("type", "invalid_request_error") }))
        for (code in listOf("session_expired", "session_closed", "session_not_found", "invalid_session", "invalid_api_key", "invalid_authentication", "authentication_error", "token_expired")) {
            assertFalse(recoverable("invalid_request_error", code))
        }
        for (type in listOf("server_error", "authentication_error", "rate_limit_error", "unknown", "")) {
            assertFalse(recoverable(type, "invalid_value"))
        }
    }

    @Test
    fun callFailuresAreReadable() {
        val limit = """{"error":{"type":"usage_limit_reached","message":"The usage limit has been reached","plan_type":"plus","resets_at":1793934081,"eligible_promo":null,"limit_window_minutes":300,"resets_in_seconds":2953}}"""
        assertEquals("ChatGPT's usage limit is reached. It resets in 49 minutes.", callFailure(429, limit))
        fun resetsIn(seconds: Long) = callFailure(429, """{"error":{"type":"usage_limit_reached","resets_in_seconds":$seconds}}""")
        assertEquals("ChatGPT's usage limit is reached. It resets in 1 minute.", resetsIn(5))
        assertEquals("ChatGPT's usage limit is reached. It resets in 1 hour.", resetsIn(3_600))
        assertEquals("ChatGPT's usage limit is reached. It resets in 2 hours 5 minutes.", resetsIn(7_500))
        assertEquals("ChatGPT's usage limit is reached. It resets in 3 hours.",
            callFailure(429, """{"error":{"type":"usage_limit_reached","resets_at":10800}}""", Instant.fromEpochSeconds(0)))
        assertEquals("ChatGPT's usage limit is reached.", callFailure(429, """{"error":{"type":"usage_limit_reached"}}"""))
        assertEquals("ChatGPT is busy. Try again in a moment.", callFailure(429, """{"error":{"type":"rate_limit_exceeded"}}"""))
        assertEquals("ChatGPT is busy. Try again in a moment.", callFailure(429, "Too many"))
        assertEquals(SIGN_IN, callFailure(401, "{}"))
        assertEquals(SIGN_IN, callFailure(403, ""))
        assertEquals("Couldn't start the call (HTTP 500).", callFailure(500, "<html>"))
    }

    @Test
    fun realtimeErrorsShowTheirMessage() {
        assertEquals("Session expired", realtimeErrorMessage(buildJsonObject { put("type", "invalid_request_error"); put("message", "Session expired") }))
        assertEquals("The call ended unexpectedly.", realtimeErrorMessage(buildJsonObject { put("type", "server_error") }))
    }
}
