package kinuvo.harness.codex

import kinuvo.harness.core.voice.AgentProgress
import kinuvo.harness.core.voice.VoiceOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class CodexRealtimeTest {
    @Test fun notificationUsesADeveloperConversationItem() {
        assertEquals("""{"type":"conversation.item.create","item":{"type":"message","role":"developer","content":[{"type":"input_text","text":"finished"}]}}""", notificationItem("finished").toString())
    }

    @Test fun progressKeepsApprovalsOnScreenAndBoundsDetails() {
        val text = AgentProgress.ApprovalRequested("shell", "x".repeat(5000)).voiceText()
        assertTrue(text.contains("never by voice"))
        assertTrue(text.length < 600)
        assertTrue(AgentProgress.ApprovalResolved(false).voiceText().contains("denied"))
        assertTrue(AgentProgress.ApprovalResolved(null).voiceText().contains("do not assume"))
    }

    @Test fun unsupportedPlatformsNeverLoadNatives() {
        for ((os, arch) in listOf("Linux" to "aarch64", "Windows 11" to "amd64", "Mac OS X" to "x86_64")) {
            assertFalse(realtimeAvailable(os, arch) { error("Must not probe unsupported platforms") })
        }
        assertFalse(realtimeAvailable("Mac OS X", "aarch64") { throw UnsatisfiedLinkError("missing native") })
        assertFalse(realtimeAvailable("Mac OS X", "arm64") { throw ExceptionInInitializerError("native initialization") })
        assertTrue(realtimeAvailable("Mac OS X", "aarch64") {})
    }

    @Test fun interruptionDiscardsBufferedAndLateAudioUntilNextAssistantTurn() {
        val reply = ReplyAudio(packetBytes = 8)
        reply.started("a1")
        assertTrue(reply.heard(sound(1)).isEmpty())
        assertTrue(reply.interrupt("stale").isEmpty())
        assertEquals(listOf(VoiceOutput.Interrupted), reply.interrupt("a1"))
        assertTrue(reply.hear(sound(2), sound(3)).isEmpty())
        reply.textDone()
        assertTrue(reply.started("a2").isEmpty())
        assertEquals((sound(4) + sound(5)).toList(), reply.hear(sound(4), sound(5)).audio("a2"))
    }

    @Test fun userTurnFlushesDeviceAudioEvenAfterServerFinishedRelaying() {
        val reply = ReplyAudio(packetBytes = 4, trailingFrames = 1)
        reply.started("a1")
        reply.heard(sound(1))
        reply.textDone()
        reply.heard(quiet)
        assertEquals(listOf(VoiceOutput.Interrupted), reply.interrupt())
        assertTrue(reply.interrupt().isEmpty())
    }

    @Test fun userTurnClearsAudioWaitingForAnAssistantTurn() {
        val reply = ReplyAudio(packetBytes = 8)
        reply.heard(sound(1))
        assertEquals(listOf(VoiceOutput.Interrupted), reply.interrupt())
        assertTrue(reply.heard(sound(2)).isEmpty())
        assertTrue(reply.started("a1").isEmpty())
        assertEquals((sound(3) + sound(4)).toList(), reply.hear(sound(3), sound(4)).audio("a1"))
    }

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

    private val quiet = ByteArray(4)
    private fun sound(n: Int) = ByteArray(4) { n.toByte() }
    private fun ReplyAudio.hear(vararg frames: ByteArray) = frames.flatMap { heard(it) }
    private fun List<VoiceOutput>.audio(item: String) = filterIsInstance<VoiceOutput.Audio>().filter { it.item == item }.flatMap { it.pcm.toList() }

    @Test
    fun replyStartsFromItsFirstSoundHeardBeforeTheTurn() {
        val reply = ReplyAudio(packetBytes = 8, trailingFrames = 2, recentFrames = 4)
        assertEquals(emptyList(), reply.hear(quiet, quiet, sound(1), sound(2)))
        val out = reply.started("t1") + reply.hear(quiet, sound(3), quiet)
        assertEquals((sound(1) + sound(2) + quiet + sound(3)).toList(), out.audio("t1"))
        reply.textDone()
        val end = reply.hear(quiet, quiet, sound(4))
        assertEquals((quiet + quiet + quiet).toList(), end.audio("t1"), "Quiet within the reply is kept until the trailing quiet ends it")
        assertEquals((sound(4) + sound(5)).toList(), (reply.started("t2") + reply.hear(sound(5))).audio("t2"))
    }

    @Test
    fun soundWithoutATurnIsRelayedBeforeItFallsOutOfTheBuffer() {
        val reply = ReplyAudio(packetBytes = 4, trailingFrames = 2, recentFrames = 3)
        val out = reply.hear(quiet, sound(1), sound(2), sound(3), sound(4), quiet, quiet, sound(5))
        val item = assertIs<VoiceOutput.Audio>(out.first()).item
        assertEquals((sound(1) + sound(2) + sound(3) + sound(4) + quiet + quiet).toList(), out.audio(item))
        assertEquals(VoiceOutput.Done(item), out.last())
        assertEquals(sound(5).toList(), (reply.started("t1")).audio("t1"))
    }

    @Test
    fun aTurnTakesOverUnannouncedSound() {
        val reply = ReplyAudio(packetBytes = 4, trailingFrames = 2, recentFrames = 1)
        val early = reply.hear(sound(1), sound(2))
        val out = reply.started("t1") + reply.hear(sound(3), quiet, quiet)
        assertEquals((sound(1) + sound(2)).toList(), early.audio(assertIs<VoiceOutput.Audio>(early.first()).item))
        assertEquals((sound(3) + quiet + quiet).toList(), out.audio("t1"))
        assertTrue(out.none { it is VoiceOutput.Done }, "A turn ends when its text is done")
    }

    @Test
    fun transcriptsStreamUntilTheFinalTextReplacesThem() {
        val transcripts = Transcripts()
        transcripts.started(user = false, turn = "a1")
        transcripts.started(user = true, turn = "u1")
        assertEquals(VoiceOutput.Transcript("a1", "Hi", false, final = false), transcripts.added(false, "Hi"))
        assertEquals(VoiceOutput.Transcript("a1", "Hi there", false, final = false), transcripts.added(false, " there"))
        assertEquals(VoiceOutput.Transcript("u1", "Hello", true, final = false), transcripts.added(true, " Hello"))
        assertEquals(null, transcripts.added(false, ""))
        assertEquals(VoiceOutput.Transcript("a1", "Hi there!", false), transcripts.done(false, "a1", "Hi there! "))
        transcripts.started(user = false, turn = "a2")
        assertEquals(VoiceOutput.Transcript("a2", "Next", false, final = false), transcripts.added(false, "Next"))
    }
}
