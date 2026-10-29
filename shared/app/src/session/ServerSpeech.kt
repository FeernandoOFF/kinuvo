package kinuvo.app.session

import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlin.coroutines.resume

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
    private var request: String? = null
    private var speakingRequest: String? = null
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
        cancel(request); recognition?.cancel()
        val recording = ++recordingGeneration
        final = onFinal; failure = onError
        val epoch = generation
        fun listening() = current(epoch) && recording == recordingGeneration
        audio?.record(untilPause, { pcm ->
            if (!listening()) return@record
            if (pcm.isEmpty()) { final(""); return@record }
            recognition = server.scope.launch {
                val id = Uuid.random().toString(); request = id
                try {
                    require(pcm.size <= 960000)
                    val encoded = withContext(audioDispatcher) { Base64.encode(pcm) }
                    val text = bounded(135_000, "Server transcription", id) {
                        server.call { client -> withContext(audioDispatcher) { client.call(Api.VoiceTranscribe, VoiceRecording(id, encoded)) } }
                    }
                    if (listening()) final(text)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (listening()) failure(e.message ?: "Server transcription failed") }
                finally { if (request == id) request = null }
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
            val id = Uuid.random().toString(); speakingRequest = id
            try {
                val clip = bounded(135_000, "Server synthesis", id) {
                    server.call { client -> withContext(audioDispatcher) { client.call(Api.VoiceSynthesize, VoiceText(id, text)) } }
                }
                if (!current(epoch) || spokenEpoch != speakingGeneration) return@launch
                require(clip.wav.length <= 11_184_812)
                val wav = withContext(audioDispatcher) { Base64.decode(clip.wav) }
                if (!current(epoch) || spokenEpoch != speakingGeneration) return@launch
                bounded(130_000, "Voice playback") {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        audio?.play(wav, { if (current(epoch) && spokenEpoch == speakingGeneration) { onDone(); if (continuation.isActive) continuation.resume(Unit) } }, {
                            if (current(epoch) && spokenEpoch == speakingGeneration) onError(it)
                            if (continuation.isActive) continuation.resume(Unit)
                        }) ?: error("Device audio playback unavailable")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { audio?.stopPlayback(); if (current(epoch) && spokenEpoch == speakingGeneration) onError(e.message ?: "Server synthesis failed") }
            finally { if (speakingRequest == id) speakingRequest = null }
        }
        speech = task
        utterances += task
        task.invokeOnCompletion { utterances -= task }
        task.start()
    }
    private suspend fun <T> bounded(millis: Long, operation: String, id: String? = null, block: suspend () -> T): T = try {
        withTimeout(millis) { block() }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        cancel(id)
        throw IllegalStateException("$operation timed out. Check the Server connection and try again.", e)
    }
    private fun cancel(id: String?) { if (id != null && serverGeneration == server.generation) server.scope.launch { runCatching { server.call { it.call(Api.VoiceCancel, id) } } } }
    override fun stopSpeaking() { speakingGeneration++; cancel(speakingRequest); utterances.toList().forEach { it.cancel() }; utterances.clear(); speech = null; audio?.stopPlayback(); device.stopSpeaking() }
    override fun stop() { generation++; recordingGeneration++; cancel(request); recognition?.cancel(); audio?.stopRecording(); stopSpeaking(); device.stop() }
}
