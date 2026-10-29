package kinuvo.app.session

import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlin.io.encoding.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Device audio only; Server inference never needs speech-recognition permission. */
interface VoiceAudio {
    val audioSupported: Boolean get() = true
    fun authorizeAudio(completion: (String?) -> Unit)
    fun record(untilPause: Boolean, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun finishRecording()
    fun stopRecording()
    fun play(wav: ByteArray, onDone: () -> Unit, onError: (String) -> Unit)
    fun stopPlayback()
}

class ServerSpeech(private val device: SpeechEngine, private val server: ConnectedServer,
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeechEngine {
    override val halfDuplex = true
    private val audio get() = (device as? VoiceAudio)?.takeIf { it.audioSupported }
    private var final: (String) -> Unit = {}
    private var failure: (String) -> Unit = {}
    private var setup = VoiceSetup()
    private var generation = 0
    private var recordingGeneration = 0
    private var serverGeneration = -1L
    private fun current(epoch: Int) = epoch == generation && serverGeneration == server.generation
    private var recognition: Job? = null
    private var speech: Job? = null
    private val utterances = mutableSetOf<Job>()
    private var speakingGeneration = 0
    override fun authorize(completion: (String?) -> Unit) {
        serverGeneration = server.generation
        val epoch = ++generation
        recognition?.cancel()
        recognition = server.scope.launch {
            try {
                setup = bounded(15_000, "Loading Server voice settings") { server.call { it.call(Api.VoiceSetup, Unit) } }
                if (!current(epoch)) return@launch
                if (setup.synthesizer && audio == null) error("This Device cannot play Server voice audio")
                if (setup.transcriber) {
                    val microphone = audio ?: error("This Device cannot record audio for Server transcription")
                    microphone.authorizeAudio { if (current(epoch)) completion(it) }
                } else device.authorize { if (current(epoch)) completion(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(epoch)) completion(e.message ?: "Server voice unavailable") }
        }
    }
    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (!setup.transcriber) { device.listen(untilPause, onPartial, onFinal, onError); return }
        recognition?.cancel()
        val recording = ++recordingGeneration
        final = onFinal; failure = onError
        val epoch = generation
        fun listening() = current(epoch) && recording == recordingGeneration
        audio?.record(untilPause, { pcm ->
            if (!listening()) return@record
            if (pcm.isEmpty()) { final(""); return@record }
            recognition = server.scope.launch {
                try {
                    require(pcm.size <= 960000)
                    val encoded = withContext(audioDispatcher) { Base64.encode(wav(pcm)) }
                    val text = bounded(135_000, "Server transcription") {
                        server.call { client -> withContext(audioDispatcher) { client.call(Api.Transcribe, TranscribeParams(encoded)) } }
                    }
                    if (listening()) final(text)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (listening()) failure(e.message ?: "Server transcription failed") }
            }
        }, { if (listening()) failure(it) }) ?: onError("Device audio capture unavailable")
    }
    override fun finish(text: String, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (setup.transcriber) { final = onFinal; failure = onError; audio?.finishRecording() } else device.finish(text, onFinal, onError)
    }
    override fun speak(text: String, onDone: () -> Unit) = speak(text, onDone, {})
    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (!setup.synthesizer) { device.speak(text, onDone, onError); return }
        val epoch = generation
        val previous = speech
        val spokenEpoch = speakingGeneration
        val task = server.scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                for (chunk in speechChunks(text)) {
                    val clip = bounded(135_000, "Server synthesis") {
                        server.call { client -> withContext(audioDispatcher) { client.call(Api.Synthesize, SynthesizeParams(chunk)) } }
                    }
                    if (!current(epoch) || spokenEpoch != speakingGeneration) return@launch
                    require(clip.base64.length <= 11_184_812)
                    val wav = withContext(audioDispatcher) { Base64.decode(clip.base64) }
                    if (!current(epoch) || spokenEpoch != speakingGeneration) return@launch
                    bounded(130_000, "Voice playback") {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            audio?.play(wav, { if (continuation.isActive) continuation.resume(Unit) }, {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(it))
                            }) ?: error("Device audio playback unavailable")
                        }
                    }
                }
                if (current(epoch) && spokenEpoch == speakingGeneration) onDone()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { audio?.stopPlayback(); if (current(epoch) && spokenEpoch == speakingGeneration) onError(e.message ?: "Server synthesis failed") }
        }
        speech = task
        utterances += task
        task.invokeOnCompletion { utterances -= task }
        task.start()
    }
    private suspend fun <T> bounded(millis: Long, operation: String, block: suspend () -> T): T = try {
        withTimeout(millis) { block() }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("$operation timed out. Check the Server connection and try again.", e)
    }
    override fun stopSpeaking() { speakingGeneration++; utterances.toList().forEach { it.cancel() }; utterances.clear(); speech = null; audio?.stopPlayback(); device.stopSpeaking() }
    override fun stop() { generation++; recordingGeneration++; recognition?.cancel(); audio?.stopRecording(); stopSpeaking(); device.stop() }
}

/** Wraps 16 kHz mono PCM16 in a WAV header. */
internal fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
    val out = ByteArray(44 + pcm.size)
    fun text(at: Int, value: String) = value.encodeToByteArray().copyInto(out, at)
    fun int(at: Int, value: Int, bytes: Int = 4) = repeat(bytes) { out[at + it] = (value shr (8 * it)).toByte() }
    text(0, "RIFF"); int(4, 36 + pcm.size); text(8, "WAVEfmt "); int(16, 16); int(20, 1, 2); int(22, 1, 2)
    int(24, rate); int(28, rate * 2); int(32, 2, 2); int(34, 16, 2); text(36, "data"); int(40, pcm.size)
    pcm.copyInto(out, 44)
    return out
}

internal fun speechChunks(text: String): List<String> = buildList {
    var from = 0
    while (from < text.length) {
        var end = minOf(from + 240, text.length)
        if (end < text.length) {
            val space = (from until end).lastOrNull { text[it].isWhitespace() }
            if (space != null && space > from) end = space
            else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        text.substring(from, end).trim().takeIf(String::isNotEmpty)?.let(::add)
        from = end
        while (from < text.length && text[from].isWhitespace()) from++
    }
}
