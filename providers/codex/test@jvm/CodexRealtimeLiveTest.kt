package kinuvo.providers.codex

import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceInput
import kinuvo.server.plugin.VoiceOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexRealtimeLiveTest {
    @Test
    fun delegatesSpokenQuestionAndSpeaksTheAnswer() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val realtime = CodexRealtime(CodexAuth())
        val speech = say("Hey, can you check what's the weather in Paris right now?", realtime.input.sampleRate)
        val input = Channel<VoiceInput>(Channel.UNLIMITED)
        val tasks = mutableListOf<String>()
        val transcripts = mutableListOf<VoiceOutput.Transcript>()
        var audioBytes = 0
        var answered = false
        runBlocking {
            withTimeout(90_000) {
                launch {
                    realtime.converse(VoiceContext("You are a friendly voice assistant. Delegate anything needing tools or live facts."), input, { out ->
                        when (out) {
                            is VoiceOutput.Audio -> audioBytes += out.pcm.size
                            is VoiceOutput.Transcript -> transcripts += out.also { println("${if (it.user) "user" else "assistant"}: ${it.text}") }
                            is VoiceOutput.Done -> answered = true
                            else -> println(out)
                        }
                    }) { task, _ ->
                        tasks += task
                        "Paris right now: 18 degrees Celsius, sunny, light wind."
                    }
                }
                val frame = realtime.input.sampleRate / 100 * 2
                val silence = ByteArray(frame)
                var at = -100 * frame
                while (!answered) {
                    input.trySend(VoiceInput.Audio(if (at >= 0 && at + frame <= speech.size) speech.copyOfRange(at, at + frame) else silence))
                    at += frame
                    delay(10)
                }
                input.close()
            }
        }
        assertTrue(tasks.any { it.contains("Paris", ignoreCase = true) }, "tasks: $tasks")
        assertTrue(transcripts.any { !it.user && (it.text.contains("18") || it.text.contains("eighteen", ignoreCase = true)) }, "transcripts: $transcripts")
        assertTrue(audioBytes > 48_000 * 2, "audio bytes: $audioBytes")
    }

    @Test
    fun endingACallMidwayDoesNotCrash() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val realtime = CodexRealtime(CodexAuth())
        val input = Channel<VoiceInput>(Channel.UNLIMITED)
        runBlocking {
            val ready = CompletableDeferred<Unit>()
            val call = launch {
                realtime.converse(VoiceContext("You are a friendly voice assistant."), input, { if (it == VoiceOutput.Ready) ready.complete(Unit) }) { _, _ -> "" }
            }
            withTimeout(30_000) { ready.await() }
            repeat(100) { input.trySend(VoiceInput.Audio(ByteArray(480))); delay(10) }
            call.cancelAndJoin()
            repeat(5) { System.gc(); delay(1_000) }
        }
    }

    /** macOS text-to-speech as mono 16-bit PCM. */
    private fun say(text: String, sampleRate: Int): ByteArray {
        val file = File.createTempFile("say", ".wav").apply { deleteOnExit() }
        check(ProcessBuilder("say", "-o", file.path, "--data-format=LEI16@$sampleRate", text).start().waitFor() == 0)
        RandomAccessFile(file, "r").use { wav ->
            var at = 12L
            while (true) {
                wav.seek(at)
                val id = ByteArray(4).also { wav.readFully(it) }.decodeToString()
                val size = Integer.reverseBytes(wav.readInt()).toLong()
                if (id == "data") return ByteArray(size.toInt()).also { wav.readFully(it) }
                at += 8 + size
            }
        }
    }
}
