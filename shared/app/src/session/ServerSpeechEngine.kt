package kinuvo.app.session

import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.*
import kotlinx.coroutines.*

/** Main-thread microphone and WAV playback, without speech recognition or synthesis. Clips are base64 encoded. */
interface VoiceAudio {
    fun authorizeMicrophone(completion: (String?) -> Unit)
    /** Finishes after a pause, 30 seconds of total recording, or ten seconds without speech (an empty clip). */
    fun record(untilPause: Boolean, onRecorded: (String) -> Unit, onError: (String) -> Unit)
    fun finishRecording()
    fun stopRecording()
    fun play(base64: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stopPlayback()
}

/** Snapshots the Server's selection at start, while all calls and callbacks belong to its connection lifetime. */
internal class ServerSpeechEngine(
    private val device: SpeechEngine,
    private val audio: VoiceAudio?,
    private val connection: ConnectedServer,
    private val scope: CoroutineScope,
    private val setup: () -> VoiceSetup,
) : SpeechEngine {
    private var selected = VoiceSetup()
    override val halfDuplex get() = device.halfDuplex || selected.transcriber || selected.synthesizer
    private var error: (String) -> Unit = {}
    private var recording = 0
    private var authorization = 0
    private var transcription: Job? = null
    private var finish: ((String?) -> Unit)? = null
    private var completed: String? = null
    private var playback: Job? = null
    private val pending = ArrayDeque<Pair<String, () -> Unit>>()

    override fun authorize(completion: (String?) -> Unit) {
        selected = setup()
        val id = ++authorization
        val authorized: (String?) -> Unit = { if (id == authorization) completion(it) }
        if ((selected.transcriber || selected.synthesizer) && audio == null) {
            authorized("Server voice audio is unavailable on this Device")
        } else if (selected.transcriber) audio!!.authorizeMicrophone(authorized)
        else device.authorize(authorized)
    }

    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        error = onError
        if (!selected.transcriber) { device.listen(untilPause, onPartial, onFinal, onError); return }
        stopRecording()
        val generation = recording
        audio!!.record(untilPause, { clip ->
            if (generation == recording) transcription = scope.launch {
                try {
                    val text = if (clip.isEmpty()) "" else connection.call { it.call(Api.Transcribe, TranscribeParams(clip)) }
                    if (generation == recording) {
                        completed = text
                        val ended = finish.also { finish = null }
                        if (ended != null) ended(text) else onFinal(text)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (generation == recording) onError(e.message ?: "Transcription failed") }
            }
        }, { if (generation == recording) onError(it) })
    }

    override fun finish(onFinal: (String?) -> Unit) {
        if (!selected.transcriber) { device.stop(); onFinal(null); return }
        completed?.let { onFinal(it); return }
        finish = onFinal
        audio!!.finishRecording()
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!selected.synthesizer) { device.speak(text, onDone) { error(it) }; return }
        pending.addLast(text to onDone)
        if (playback?.isActive == true) return
        playback = scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    val (words, done) = pending.first()
                    for (chunk in speechChunks(words)) {
                        val clip = connection.call { it.call(Api.Synthesize, SynthesizeParams(chunk)) }
                        val played = CompletableDeferred<Unit>()
                        audio!!.play(clip.base64, { played.complete(Unit) }, { played.completeExceptionally(IllegalStateException(it)) })
                        played.await()
                    }
                    pending.removeFirst()
                    done()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Speech synthesis failed") }
        }
    }

    private fun stopRecording() {
        recording++
        finish = null
        completed = null
        transcription?.cancel()
        transcription = null
        audio?.stopRecording()
    }

    override fun stopSpeaking() {
        playback?.cancel()
        playback = null
        audio?.stopPlayback()
        device.stopSpeaking()
        val stopped = pending.toList()
        pending.clear()
        stopped.forEach { it.second() }
    }

    override fun stop() { authorization++; stopRecording(); stopSpeaking(); device.stop() }
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
