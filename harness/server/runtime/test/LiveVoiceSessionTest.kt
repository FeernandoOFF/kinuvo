package kinuvo.harness.server

import kinuvo.harness.core.voice.AgentProgress
import kinuvo.harness.core.voice.*
import kinuvo.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.io.encoding.Base64
import kotlin.test.*

class LiveVoiceSessionTest {
    @Test fun persistenceFailureStillEndsCallAndClosesInput() = runTest {
        val events = mutableListOf<LiveVoiceOutput>()
        var captured: ReceiveChannel<VoiceInput>? = null
        var released = false
        val provider = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                captured = input
                output(VoiceOutput.Transcript("u1", "hello", true))
            }
        }
        val call = LiveVoiceSession(provider, backgroundScope, VoiceContext("Agent"), {
            events += it; Delivery.Queued
        }, { _, _ -> "reply" }, { error("store unavailable") }, { AutoCloseable { released = true } })
        call.start()
        runCurrent()
        assertTrue(released)
        assertFalse(call.active)
        assertTrue(captured!!.receiveCatching().isClosed)
        assertEquals("The call transcript couldn't be saved.", assertIs<LiveVoiceOutput.Ended>(events.last()).error)
        assertEquals(1, events.count { it is LiveVoiceOutput.Ended })
    }

    @Test fun recordsOneBlockWhenDisconnectedAndSeedsLaterCalls() = runTest {
        val turns = listOf(VoiceTurn(true, "hello"), VoiceTurn(false, "hi"))
        val saved = mutableListOf<SessionEntry.VoiceCall>()
        val provider = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                turns.forEachIndexed { i, turn ->
                    output(VoiceOutput.Transcript("$i", turn.text.take(2), turn.user, final = false))
                    output(VoiceOutput.Transcript("$i", turn.text, turn.user))
                }
                for (value in input) Unit
            }
        }
        val call = LiveVoiceSession(provider, backgroundScope, VoiceContext("Agent"), { Delivery.Queued }, { _, _ -> "reply" }, { saved += it })
        call.start()
        runCurrent()
        assertTrue(saved.isEmpty())
        call.close()
        runCurrent()
        assertEquals(turns, saved.single().turns)
        assertEquals(turns.map { VoiceMessage(it.user, it.text) }, voiceContext("Agent", saved).history)
    }

    @Test fun dropsAudioTheDeviceCannotTakeButEndsWhenOtherEventsCannotBeQueued() = runTest {
        val events = mutableListOf<LiveVoiceOutput>()
        val provider = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                repeat(3) { output(VoiceOutput.Audio("reply", byteArrayOf(1, 0))) }
                output(VoiceOutput.Done("reply"))
                output(VoiceOutput.Listening)
                for (value in input) Unit
            }
        }
        val call = LiveVoiceSession(provider, backgroundScope, VoiceContext("Agent"), {
            events += it
            if (it is LiveVoiceOutput.Audio || it is LiveVoiceOutput.Listening) Delivery.Dropped else Delivery.Queued
        }, { _, _ -> "reply" })
        call.start()
        runCurrent()
        assertEquals(3, events.count { it is LiveVoiceOutput.Audio })
        assertIs<LiveVoiceOutput.Done>(events[3])
        assertEquals("Device cannot keep up with live voice", assertIs<LiveVoiceOutput.Ended>(events.last()).error)
        assertFalse(call.active)
    }

    @Test fun seedsContextWithRolesAndLatestSummary() {
        val context = voiceContext("Ada", listOf(
            SessionEntry.Compaction("old"),
            SessionEntry.UserMessage("hello"),
            SessionEntry.AssistantMessage("hi"),
            SessionEntry.Compaction("latest"),
            SessionEntry.UserMessage(" ")
        ))
        assertEquals(listOf(VoiceMessage(true, "hello"), VoiceMessage(false, "hi")), context.history)
        assertTrue(context.instructions.contains("Ada"))
        assertTrue(context.instructions.contains("Delegate"))
        assertTrue(context.instructions.contains("latest"))
        assertFalse(context.instructions.contains("old"))
    }

    @Test fun boundsContextByCountMessageSizeAndTotalSize() {
        val short = voiceContext("Ada", (0..50).map { SessionEntry.UserMessage("$it") })
        assertEquals(40, short.history.size)
        assertEquals("11", short.history.first().text)
        val large = voiceContext("Ada", (0..50).map { SessionEntry.AssistantMessage("x".repeat(3_000)) })
        assertEquals(12, large.history.size)
        assertTrue(large.history.all { !it.user && it.text.length == 2_000 })
        assertEquals(24_000, large.history.sumOf { it.text.length })
    }

    @Test fun exchangesAudioAndStops() = runTest {
        val received = Channel<VoiceInput>(Channel.UNLIMITED)
        val events = mutableListOf<LiveVoiceOutput>()
        var stopped = false
        val provider = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(48000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                try {
                    output(VoiceOutput.Ready)
                    output(VoiceOutput.Audio("reply", byteArrayOf(1, 0, 2, 0)))
                    for (value in input) received.send(value)
                } finally { stopped = true }
            }
        }
        val call = LiveVoiceSession(provider, backgroundScope, VoiceContext("Agent"), { events.add(it); Delivery.Queued }, { _, _ -> "reply" })
        val ref = call.start()
        runCurrent()
        assertIs<LiveVoiceOutput.Ready>(events.first())
        assertContentEquals(byteArrayOf(1, 0), Base64.decode(events.filterIsInstance<LiveVoiceOutput.Audio>().single().base64))
        call.accept(LiveVoiceInput.Audio(ref.callId, "AQACAA=="))
        runCurrent()
        assertContentEquals(byteArrayOf(1, 0, 2, 0), (received.receive() as VoiceInput.Audio).pcm)
        assertFailsWith<IllegalArgumentException> { call.accept(LiveVoiceInput.Audio(ref.callId, "A".repeat(65540))) }
        call.close()
        runCurrent()
        assertTrue(stopped)
        assertIs<LiveVoiceOutput.Ended>(events.last())
    }
}
