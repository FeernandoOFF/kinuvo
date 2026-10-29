package kinuvo.harness.server

import kinuvo.protocol.*
import kinuvo.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.io.encoding.Base64

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
    val providers get() = kinuvo.harness.core.voice.VoiceProviders(
        transcriber = if (store.selected(VoiceModelKind.Transcription) == null) null else kinuvo.harness.core.voice.Transcriber { audio ->
            require(audio.format == kinuvo.harness.core.voice.AudioFormat.Pcm16(16000))
            infer(VoiceModelKind.Transcription, Inference.pcmToFloat(audio.bytes)).decodeToString().also { check(it.length <= 20000) }
        },
        synthesizer = if (store.selected(VoiceModelKind.Synthesis) == null) null else kinuvo.harness.core.voice.Synthesizer { text ->
            kinuvo.harness.core.voice.Audio(infer(VoiceModelKind.Synthesis, text.encodeToByteArray()), kinuvo.harness.core.voice.AudioFormat.Wav)
        },
    )
    override fun setup() = providers.setup
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
    override suspend fun transcribe(recording: VoiceRecording): String {
        require(recording.pcm.length <= 1_280_000)
        val audio = kinuvo.harness.core.voice.Audio(Base64.decode(recording.pcm), kinuvo.harness.core.voice.AudioFormat.Pcm16(16000))
        return requireNotNull(providers.transcriber) { "Server transcription is not configured" }.transcribe(audio)
    }
    override suspend fun synthesize(text: VoiceText): VoiceClip {
        require(text.text.isNotBlank() && text.text.length <= 2000 && text.text.encodeToByteArray().size <= 8192)
        return VoiceClip(Base64.encode(requireNotNull(providers.synthesizer) { "Server synthesis is not configured" }.synthesize(text.text).bytes))
    }
    override suspend fun close() { scope.coroutineContext.job.cancelAndJoin() }
}
