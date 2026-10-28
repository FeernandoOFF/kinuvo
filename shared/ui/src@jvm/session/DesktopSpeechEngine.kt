package kinuvo.ui.session

import kinuvo.app.session.SpeechEngine
import kinuvo.app.session.VoiceAudio
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kotlinx.serialization.json.*
import java.io.File
import javax.swing.SwingUtilities

/** JSON lines to the bundled native speech helper. Callbacks run on the UI thread. */
internal class DesktopSpeechEngine(
    private val launch: () -> Process = ::launchSpeechHelper,
) : SpeechEngine, VoiceAudio {
    override val halfDuplex = true
    private var process: Process? = null
    private var next = 0
    private var listening = 0
    private var authorization: Pair<Int, (String?) -> Unit>? = null
    private var partial: (String) -> Unit = {}
    private var final: (String) -> Unit = {}
    private var error: (String) -> Unit = {}
    private val spoken = mutableMapOf<Int, () -> Unit>()
    private var recorded: (String) -> Unit = {}
    private val playbackErrors = mutableMapOf<Int, (String) -> Unit>()
    private val shutdown = Thread { process?.destroy() }

    private fun helper(): Process {
        process?.let {
            check(it.isAlive) { "Desktop speech helper stopped. Try again." }
            return it
        }
        val child = launch()
        if (process == null) runCatching { Runtime.getRuntime().addShutdownHook(shutdown) }
        process = child
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                        SwingUtilities.invokeLater { if (process === child) receive(event) }
                    }
                }
            } finally {
                SwingUtilities.invokeLater { if (process === child) failed("Desktop speech helper stopped. Try again.") }
            }
        }, "kinuvo-speech").apply { isDaemon = true; start() }
        return child
    }

    private fun command(op: String, id: Int = 0, text: String = "", untilPause: Boolean = true) {
        val line = buildJsonObject { put("op", op); put("id", id); put("text", text); put("untilPause", untilPause) }.toString() + "\n"
        helper().outputStream.run { write(line.toByteArray(Charsets.UTF_8)); flush() }
    }

    private fun receive(event: JsonObject) {
        val id = event["id"]?.jsonPrimitive?.intOrNull ?: 0
        val text = event["text"]?.jsonPrimitive?.content.orEmpty()
        when (event["event"]?.jsonPrimitive?.content) {
            "authorized" -> if (authorization?.first == id) authorization?.also { authorization = null }?.second?.invoke(text.ifBlank { null })
            "partial" -> if (id == listening && id != 0) partial(text)
            "final" -> if (id == listening && id != 0) { listening = 0; final(text) }
            "recorded" -> if (id == listening && id != 0) { listening = 0; recorded(text) }
            "error" -> if (id == listening && id != 0 || id in spoken) {
                listening = 0
                (playbackErrors.remove(id) ?: error)(text)
                spoken.remove(id)?.invoke()
            }
            "done" -> { playbackErrors.remove(id); spoken.remove(id)?.invoke() }
        }
    }

    private fun failed(message: String) {
        process?.destroy()
        process = null
        val auth = authorization?.second
        authorization = null
        val active = listening != 0 || spoken.isNotEmpty()
        listening = 0
        auth?.invoke(message)
        if (active) (playbackErrors.values.firstOrNull() ?: error)(message)
        finishSpeaking()
    }

    override fun authorize(completion: (String?) -> Unit) {
        val id = ++next
        authorization = id to completion
        runCatching { command("authorize", id) }.onFailure { failed(it.message ?: "Cannot start desktop speech") }
    }

    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        partial = onPartial; final = onFinal; error = onError
        listening = ++next
        runCatching { command("listen", listening, untilPause = untilPause) }.onFailure { failed(it.message ?: "Cannot listen") }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        speak(text, onDone) { error(it) }
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val id = ++next
        spoken[id] = onDone
        playbackErrors[id] = onError
        runCatching { command("speak", id, text) }.onFailure { failed(it.message ?: "Cannot speak") }
    }

    private fun finishSpeaking() {
        val callbacks = spoken.values.toList()
        spoken.clear()
        playbackErrors.clear()
        callbacks.forEach { it() }
    }

    override fun stopSpeaking() {
        if (process?.isAlive == true) runCatching { command("stopSpeaking") }.onFailure { failed(it.message ?: "Cannot stop speech") }
        finishSpeaking()
    }

    override fun stop() {
        authorization = null
        listening = 0
        if (process?.isAlive == true) runCatching { command("stop") }.onFailure { failed(it.message ?: "Cannot stop listening") }
        stopSpeaking()
    }

    override fun authorizeMicrophone(completion: (String?) -> Unit) {
        val id = ++next
        authorization = id to completion
        runCatching { command("authorizeMicrophone", id) }.onFailure { failed("Cannot access the microphone") }
    }

    override fun record(untilPause: Boolean, onRecorded: (String) -> Unit, onError: (String) -> Unit) {
        recorded = onRecorded; error = onError
        listening = ++next
        runCatching { command("record", listening, untilPause = untilPause) }.onFailure { failed("Cannot record audio") }
    }

    override fun finishRecording() { runCatching { command("finishRecording") }.onFailure { failed("Cannot finish recording") } }
    override fun stopRecording() { listening = 0; if (process?.isAlive == true) runCatching { command("stopRecording") } }

    override fun play(base64: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val id = ++next
        spoken[id] = onDone
        playbackErrors[id] = onError
        runCatching { command("play", id, base64) }.onFailure { failed("Cannot play audio") }
    }

    override fun stopPlayback() {
        if (process?.isAlive == true) runCatching { command("stopPlayback") }
        finishSpeaking()
    }
}

private fun launchSpeechHelper(): Process {
    val relative = if (currentPlatform == Platform.MacOS) "KinuvoSpeech.app/Contents/MacOS/KinuvoSpeech" else "KinuvoSpeech.exe"
    val path = System.getenv("KINUVO_SPEECH_HELPER")
        ?: File(System.getProperty("compose.application.resources.dir", "build"), relative).path
    check(File(path).isFile) { "Desktop speech helper missing. Build it and launch with mise run desktop; see docs/desktop-voice.md." }
    return ProcessBuilder(path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
}
