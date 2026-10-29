package kinuvo.harness.server

import kinuvo.harness.core.voice.*
import kinuvo.protocol.*
import kinuvo.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class DownloadedVoice(home: File, private val inference: Inference = Inference(Inference.bundled())) : ServerVoice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = File(home, "server-voice/settings.json")
    private var values = if (settings.exists()) ProtocolJson.decodeFromString<Map<String, String>>(settings.readText()) else emptyMap()
    private val store = ModelStore(File(home, "server-voice/models"), object : VoicePreferences {
        @Synchronized override fun get(key: String) = values[key]
        @Synchronized override fun set(key: String, value: String?) {
            val next = if (value == null) values - key else values + (key to value)
            kinuvo.harness.core.store.writeAtomically(kotlinx.io.files.Path(settings.path), ProtocolJson.encodeToString(next))
            values = next
        }
    })
    private val management = Mutex()
    @Volatile private var job: Job? = null
    @Volatile private var downloading: String? = null
    @Volatile private var downloaded = 0L
    @Volatile private var error: String? = null
    override fun state() = VoiceModelsState(store.models, store.installed(), store.selected(VoiceModelKind.Transcription), store.selected(VoiceModelKind.Synthesis), store.language, downloading, downloaded, error, inference.supported)
    override val providers get() = VoiceProviders(
        transcriber = if (store.selected(VoiceModelKind.Transcription) == null) null else Transcriber { audio ->
            val pcm = when (audio.format) {
                AudioFormat.Pcm16(16000) -> audio.bytes
                AudioFormat.Wav -> wavPcm16(audio.bytes)
                else -> throw IllegalArgumentException("Downloaded models need 16 kHz mono PCM")
            }
            infer(VoiceModelKind.Transcription, Inference.pcmToFloat(pcm)).decodeToString().also { check(it.length <= 20000) }
        },
        synthesizer = if (store.selected(VoiceModelKind.Synthesis) == null) null else Synthesizer { text ->
            require(text.isNotBlank() && text.length <= 2000 && text.encodeToByteArray().size <= 8192)
            Audio(infer(VoiceModelKind.Synthesis, text.encodeToByteArray()), AudioFormat.Wav)
        },
    )
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized override fun download(id: String) {
        check(inference.supported) { "Downloaded models require the packaged macOS Server" }
        check(job?.isCompleted != false) { "A download is already active" }
        val model = store.models.single { it.id == id }
        downloading = id; downloaded = 0; error = null
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            try { store.download(model) { downloaded = it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Download failed" }
            finally { downloading = null; downloaded = 0 }
        }
    }
    override fun cancelDownload() { job?.cancel() }
    override suspend fun remove(id: String) = management.withLock {
        check(downloading == null) { "Wait for the download to finish" }
        val model = store.models.single { it.id == id }
        store.remove(model)
        if (store.selected(model.kind) == id) store.select(model.kind, null)
    }
    override suspend fun select(selection: VoiceSelection) = management.withLock {
        require(selection.id == null || store.models.any { it.id == selection.id && it.kind == selection.kind && it.id in store.installed() }) { "Download this model first" }
        check(selection.id == null || inference.supported) { "Server voice inference unavailable" }
        store.select(selection.kind, selection.id)
    }
    override fun language(language: String) { require(language in VOICE_LANGUAGES); store.language = language }
    private suspend fun infer(kind: VoiceModelKind, input: ByteArray): ByteArray {
        val model = store.models.singleOrNull { it.id == store.selected(kind) && it.kind == kind } ?: error("Server voice model is not configured")
        store.acquire(model)
        try { return inference.run(if (kind == VoiceModelKind.Transcription) "transcribe" else "synthesize", store.directory(model), store.language, model.id.removePrefix("whisper-"), input) }
        finally { store.release(model) }
    }
    override suspend fun close() { scope.coroutineContext.job.cancelAndJoin() }
}

/** The 16 kHz mono 16-bit samples of a WAV recording. */
internal fun wavPcm16(wav: ByteArray): ByteArray {
    val bytes = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    require(wav.size >= 12 && String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "Invalid WAV recording" }
    var at = 12
    var format = false
    while (at + 8 <= wav.size) {
        val id = String(wav, at, 4)
        val size = bytes.getInt(at + 4)
        require(size >= 0 && at + 8 + size <= wav.size || id == "data") { "Invalid WAV recording" }
        if (id == "fmt ") {
            format = bytes.getShort(at + 8).toInt() == 1 && bytes.getShort(at + 10).toInt() == 1 &&
                bytes.getInt(at + 12) == 16000 && bytes.getShort(at + 22).toInt() == 16
        } else if (id == "data") {
            require(format) { "Downloaded models need 16 kHz mono 16-bit WAV" }
            return wav.copyOfRange(at + 8, minOf(wav.size, at + 8 + size))
        }
        at += 8 + size + (size and 1)
    }
    throw IllegalArgumentException("Invalid WAV recording")
}
