package kinuvo.ui.session

import kinuvo.app.session.SpeechEngine
import kinuvo.app.session.VoiceAudio
import kotlin.io.encoding.Base64
import kinuvo.app.voice.*
import kinuvo.ui.voice.DesktopVoiceRepository
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kotlinx.serialization.json.*
import javax.swing.SwingUtilities

/** Native speech commands and events. Callbacks run on the UI thread. */
internal class DesktopSpeechEngine(
    private val local: DesktopVoiceRepository? = null,
    private val launch: () -> SpeechTransport = ::desktopSpeechTransport,
) : SpeechEngine, VoiceAudio {
    override val halfDuplex = true
    override val audioSupported get() = currentPlatform == Platform.MacOS
    @Volatile private var transport: SpeechTransport? = null
    private var next = 0
    private var listening = 0
    private var authorization: Pair<Int, (String?) -> Unit>? = null
    private var recorded: (ByteArray) -> Unit = {}
    private var partial: (String) -> Unit = {}
    private var final: (String) -> Unit = {}
    private var error: (String) -> Unit = {}
    private val spoken = mutableMapOf<Int, Pair<() -> Unit, (String) -> Unit>>()
    private val leases = mutableMapOf<Int, VoiceModel>()
    private var localListening = false
    private data class Configuration(val transcription: VoiceModel?, val synthesis: VoiceModel?, val language: String)
    private var configuration: Configuration? = null
    private fun configuration() = configuration ?: Configuration(selected(VoiceModelKind.Transcription), selected(VoiceModelKind.Synthesis), local?.language ?: "en").also { configuration = it }
    private val shutdown = Thread { transport?.close() }

    private fun connection(): SpeechTransport {
        transport?.let { return it }
        val connection = launch()
        runCatching { Runtime.getRuntime().addShutdownHook(shutdown) }
        transport = connection
        Thread({
            try {
                while (transport === connection) {
                    val line = connection.poll()
                    if (line == null) { Thread.sleep(10); continue }
                    val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    SwingUtilities.invokeLater { if (transport === connection) receive(event) }
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater { if (transport === connection) failed(e.message ?: "Desktop speech stopped. Try again.") }
            }
        }, "kinuvo-speech-events").apply { isDaemon = true; start() }
        return connection
    }

    private fun command(op: String, id: Int = 0, text: String = "", untilPause: Boolean = true, model: VoiceModel? = null) {
        if (model != null && op != "localAuthorize") {
            checkNotNull(local).acquire(model)
            leases.put(id, model)?.let { local.release(it) }
        } else if (model != null) check(model.id in checkNotNull(local).installed()) { "Local voice model is missing. Download it again in Device-local voice settings." }
        val line = buildJsonObject {
            put("op", op); put("id", id); put("text", text); put("untilPause", untilPause)
            if (model != null && local != null) {
                put("modelDir", local.directory(model).absolutePath)
                put("variant", model.id.removePrefix("whisper-"))
                put("language", configuration().language)
                put("workerPath", java.io.File(speechResources(), "voice-worker/MacOS/KinuvoVoiceInference").absolutePath)
            }
        }.toString()
        connection().send(line)
    }

    private fun receive(event: JsonObject) {
        val id = event["id"]?.jsonPrimitive?.intOrNull ?: 0
        val text = event["text"]?.jsonPrimitive?.content.orEmpty()
        val kind = event["event"]?.jsonPrimitive?.content
        if (kind in listOf("final", "error", "done", "released")) leases.remove(id)?.let { local?.release(it) }
        when (kind) {
            "audio" -> if (id == listening && id != 0) { listening = 0; runCatching { Base64.decode(text) }.onSuccess(recorded).onFailure { error("Invalid recorded audio") } }
            "authorized" -> if (authorization?.first == id) authorization?.also { authorization = null }?.second?.invoke(text.ifBlank { null })
            "partial" -> if (id == listening && id != 0) partial(text)
            "final" -> if (id == listening && id != 0) { listening = 0; final(text) }
            "error" -> {
                if (id == listening && id != 0) { listening = 0; error(text) }
                spoken.remove(id)?.second?.invoke(text)
            }
            "done" -> spoken.remove(id)?.first?.invoke()
        }
    }

    private fun failed(message: String) {
        val connection = transport
        transport = null
        runCatching { connection?.close() }
        val held = leases.values.toList()
        leases.clear()
        held.forEach { local?.release(it) }
        val auth = authorization?.second
        authorization = null
        val active = listening != 0
        listening = 0
        val pending = spoken.values.toList()
        spoken.clear()
        auth?.invoke(message)
        if (active) error(message)
        pending.forEach { it.second(message) }
    }

    override fun authorizeAudio(completion: (String?) -> Unit) {
        val id = ++next; authorization = id to completion
        runCatching { command("localAuthorize", id) }.onFailure { failed(it.message ?: "Cannot authorize microphone") }
    }
    override fun record(untilPause: Boolean, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) {
        recorded = onAudio; error = onError; listening = ++next
        runCatching { command("record", listening, untilPause = untilPause) }.onFailure { failed(it.message ?: "Cannot record") }
    }
    override fun finishRecording() { runCatching { command("localFinish", listening) }.onFailure { failed(it.message ?: "Cannot finish recording") } }
    override fun stopRecording() { listening = 0; if (transport != null) runCatching { command("stop") } }
    override fun play(wav: ByteArray, onDone: () -> Unit, onError: (String) -> Unit) {
        val id = ++next; spoken[id] = onDone to onError
        runCatching { command("playClip", id, Base64.encode(wav)) }.onFailure { failed(it.message ?: "Cannot play audio") }
    }
    override fun stopPlayback() = stopSpeaking()

    override fun authorize(completion: (String?) -> Unit) {
        configuration = null
        val settings = configuration()
        val id = ++next
        authorization = id to completion
        runCatching { command(if (settings.transcription != null) "localAuthorize" else "authorize", id, model = settings.transcription) }.onFailure { failed(it.message ?: "Cannot start desktop speech") }
    }

    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        partial = onPartial; final = onFinal; error = onError
        listening = ++next
        val model = configuration().transcription
        localListening = model != null
        runCatching { command(if (model != null) "localListen" else "listen", listening, untilPause = untilPause, model = model) }.onFailure { failed(it.message ?: "Cannot listen") }
    }

    private fun selected(kind: VoiceModelKind) = local?.selected(kind)?.let { id -> local.models.firstOrNull { it.id == id } }

    override fun finish(text: String, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (!localListening || listening == 0) { stop(); onFinal(text); return }
        final = onFinal; error = onError
        runCatching { command("localFinish", listening) }.onFailure { failed(it.message ?: "Cannot finish recording") }
    }

    override fun speak(text: String, onDone: () -> Unit) = speak(text, onDone) { error(it); onDone() }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val id = ++next
        spoken[id] = onDone to onError
        val model = configuration().synthesis
        runCatching { command(if (model != null) "localSpeak" else "speak", id, text, model = model) }.onFailure { failed(it.message ?: "Cannot speak") }
    }

    private fun finishSpeaking() {
        val callbacks = spoken.values.toList()
        spoken.clear()
        callbacks.forEach { it.first() }
    }

    override fun stopSpeaking() {
        if (transport != null) runCatching { command("stopSpeaking") }.onFailure { failed(it.message ?: "Cannot stop speech") }
        finishSpeaking()
    }

    override fun stop() {
        authorization = null
        listening = 0
        if (transport != null) runCatching { command("stop") }.onFailure { failed(it.message ?: "Cannot stop listening") }
        stopSpeaking()
    }
}
