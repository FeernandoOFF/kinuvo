package kinuvo.harness.server

import kinuvo.server.plugin.AgentProgress
import kinuvo.server.plugin.SpeechToSpeech
import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceInput
import kinuvo.server.plugin.VoiceMessage
import kinuvo.server.plugin.VoiceOutput
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("kinuvo.voice")

/** What became of an event offered to the Device: audio may be dropped when it falls behind, other events never are. */
internal enum class Delivery { Queued, Dropped, Closed }

internal class LiveVoiceSession(
    private val provider: SpeechToSpeech,
    private val scope: CoroutineScope,
    private val context: VoiceContext,
    private val emit: (LiveVoiceOutput) -> Delivery,
    private val askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String,
    private val record: suspend (SessionEntry.VoiceCall) -> Unit = {},
    private val keepAwake: () -> AutoCloseable = { AutoCloseable {} },
) {
    val id = Uuid.random().toString()
    private val input = Channel<VoiceInput>(64)
    private var job: Job? = null
    private var heard = 0L
    private var said = 0L
    private var dropped = 0L
    private var droppedReported = 0L
    private var reported = TimeSource.Monotonic.markNow()
    @Volatile private var ended = false
    val active get() = !ended && job?.isActive == true

    suspend fun notify(text: String) {
        if (!active) return
        try { provider.notify(text, input) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.warn(e) { "Live voice $id could not receive a report" } }
    }

    fun start(): LiveVoiceCall {
        log.info { "Live voice $id starting" }
        job = scope.launch {
            val awake = keepAwake()
            var error: String? = null
            val startedAt = Clock.System.now().toEpochMilliseconds()
            val elapsed = TimeSource.Monotonic.markNow()
            val turns = mutableListOf<VoiceTurn>()
            try {
                provider.converse(context, input, { value ->
                    if (value is VoiceOutput.Transcript && value.final && value.text.isNotBlank()) turns += VoiceTurn(value.user, value.text)
                    val event = when (value) {
                        VoiceOutput.Ready -> LiveVoiceOutput.Ready(id)
                        VoiceOutput.Listening -> LiveVoiceOutput.Listening(id)
                        VoiceOutput.Thinking -> LiveVoiceOutput.Thinking(id)
                        is VoiceOutput.Audio -> LiveVoiceOutput.Audio(id, value.item, Base64.encode(resample(value.pcm, provider.output.sampleRate, 24000)))
                        is VoiceOutput.Transcript -> LiveVoiceOutput.Transcript(id, value.item, value.text, value.user, value.final)
                        is VoiceOutput.Done -> LiveVoiceOutput.Done(id, value.item)
                        VoiceOutput.Interrupted -> LiveVoiceOutput.Interrupted(id)
                    }
                    if (event is LiveVoiceOutput.Audio) said += event.base64.length
                    else if (event !is LiveVoiceOutput.Transcript || event.final) log.info { "Live voice $id: ${event::class.simpleName}" }
                    when (emit(event)) {
                        Delivery.Queued -> {}
                        Delivery.Dropped -> if (event is LiveVoiceOutput.Audio) dropped++ else error("Device cannot keep up with live voice")
                        Delivery.Closed -> error("Device disconnected")
                    }
                    report()
                }, askAgent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.warn(e) { "Live voice $id failed" }; error = e.message?.take(300) ?: "Live voice failed. Please try again." }
            finally {
                try {
                    awake.close()
                } finally {
                    try {
                        if (turns.isNotEmpty()) withContext(NonCancellable) { record(SessionEntry.VoiceCall(id, turns.toList(), startedAt, elapsed.elapsedNow().inWholeMilliseconds)) }
                    } catch (e: Exception) {
                        log.warn(e) { "Live voice $id transcript could not be saved" }
                        if (error == null) error = "The call transcript couldn't be saved."
                    } finally {
                        log.info { "Live voice $id ended; heard ${heard / 1024} KiB, said ${said / 1024} KiB, dropped $dropped packets" }
                        ended = true
                        input.close()
                        emit(LiveVoiceOutput.Ended(id, error))
                    }
                }
            }
        }
        return LiveVoiceCall(id, 24000)
    }

    fun accept(value: LiveVoiceInput) {
        if (value.callId != id || !active) return
        val mapped = when (value) {
            is LiveVoiceInput.Audio -> {
                require(value.base64.length <= 65536) { "Voice audio is too large" }
                val bytes = Base64.decode(value.base64)
                heard += bytes.size
                report()
                require(bytes.size % 2 == 0)
                VoiceInput.Audio(resample(bytes, 24000, provider.input.sampleRate))
            }
            is LiveVoiceInput.Played -> VoiceInput.Played(value.item, value.millis)
            is LiveVoiceInput.Interrupt -> VoiceInput.Interrupt(value.item, value.millis)
            is LiveVoiceInput.Pause -> VoiceInput.Pause
            is LiveVoiceInput.Resume -> VoiceInput.Resume
        }
        require(input.trySend(mapped).isSuccess) { "Voice input queue is full" }
    }

    fun close() { input.close(); job?.cancel() }

    private fun report() {
        if (reported.elapsedNow().inWholeSeconds < 5) return
        reported = TimeSource.Monotonic.markNow()
        log.info { "Live voice $id: heard ${heard / 1024} KiB, said ${said / 1024} KiB" }
        if (dropped > droppedReported) log.warn { "Live voice $id: the Device fell behind; dropped ${dropped - droppedReported} audio packets" }
        droppedReported = dropped
    }
}

internal fun resample(bytes: ByteArray, from: Int, to: Int): ByteArray {
    if (from == to) return bytes
    val samples = bytes.size / 2
    if (samples == 0) return bytes
    fun sample(i: Int) = (bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)
    return ByteArray(samples * to / from * 2).also { result ->
        for (i in 0 until result.size / 2) {
            // Averages what each output sample spans, so downsampling doesn't alias.
            val start = (i.toLong() * from / to).toInt().coerceAtMost(samples - 1)
            val end = ((i + 1L) * from / to).toInt().coerceIn(start + 1, samples)
            val value = (start until end).sumOf { sample(it) } / (end - start)
            result[i * 2] = value.toByte()
            result[i * 2 + 1] = (value shr 8).toByte()
        }
    }
}

/** The Agent the model speaks for and the Session's latest messages; older ones are left to the last compaction summary. */
internal fun voiceContext(agentName: String, entries: List<SessionEntry>): VoiceContext {
    val summary = entries.filterIsInstance<SessionEntry.Compaction>().lastOrNull()?.summary?.takeIf { it.isNotBlank() }
    val messages = entries.flatMap {
        if (it is SessionEntry.VoiceCall) it.turns.map { turn -> VoiceMessage(turn.user, turn.text) }
        else listOfNotNull(when (it) {
            is SessionEntry.UserMessage -> VoiceMessage(true, it.text)
            is SessionEntry.AssistantMessage -> VoiceMessage(false, it.text)
            else -> null
        })
    }.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.take(2_000)) }
    var budget = 24_000
    val recent = messages.asReversed().takeWhile { budget -= it.text.length; budget >= 0 }.take(40).reversed()
    val instructions = buildString {
        append("You are the voice of $agentName, talking with the user. Speak naturally and casually, using short sentences. Brief fillers like 'hmm' or 'let me check' are fine when natural. Always acknowledge the request aloud before delegating. Delegate anything needing tools, memory, the user's data or live facts to the Agent. Give short spoken updates while waiting, based on Agent progress; do not invent progress or leave a long silence. Relay approval requests and direct the user to review and approve or deny them in the app, never by voice. Relay the Agent's questions and ask the user to answer in the app. Clearly announce when results come back, then briefly explain the outcome.")
        if (summary != null) append("\n\nEarlier in this conversation:\n").append(summary.take(6_000))
    }
    return VoiceContext(instructions, recent)
}
