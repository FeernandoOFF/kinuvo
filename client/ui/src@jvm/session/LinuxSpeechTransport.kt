package kinuvo.ui.session

import java.util.Locale
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.json.*

internal fun linuxSpeechCommand(program: String, text: String, language: String): List<String> = when (program) {
    "spd-say" -> listOf(program, "-w", "-l", language, "--", text)
    else -> listOf(program, "-v", language, "--", text)
}

internal class LinuxSpeechTransport(
    private val run: (List<String>) -> Process = { ProcessBuilder(it).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() },
    private val language: String = Locale.getDefault().language.ifBlank { "en" },
) : SpeechTransport {
    private val events = ConcurrentLinkedQueue<String>()
    private val pending = Executors.newSingleThreadExecutor { Thread(it, "kinuvo-linux-speech").apply { isDaemon = true } }
    private var generation = 0
    private var process: Process? = null
    private var dispatcher = false
    private fun emit(kind: String, id: Int, text: String = "") {
        events.add(buildJsonObject { put("event", kind); put("id", id); put("text", text) }.toString())
    }
    @Synchronized override fun send(command: String) {
        val value = Json.parseToJsonElement(command).jsonObject
        val id = value["id"]?.jsonPrimitive?.int ?: 0
        when (value["op"]?.jsonPrimitive?.content) {
            "authorize" -> emit("authorized", id, "Device speech recognition is unavailable on Linux. Use Server voice with downloaded Whisper instead.")
            "listen" -> emit("error", id, "Device speech recognition is unavailable on Linux. Use Server voice with downloaded Whisper instead.")
            "stop", "stopSpeaking" -> cancel()
            "speak" -> {
                val epoch = generation
                val text = value["text"]!!.jsonPrimitive.content
                pending.submit {
                    try {
                        val child = synchronized(this) {
                            if (epoch != generation) return@submit
                            val child = try { run(linuxSpeechCommand("spd-say", text, language)).also { dispatcher = true } }
                            catch (_: IOException) {
                                dispatcher = false
                                try { run(linuxSpeechCommand("espeak-ng", text, language)) }
                                catch (_: IOException) { error("Install speech-dispatcher or espeak-ng for spoken replies") }
                            }
                            process = child
                            child
                        }
                        val status = child.waitFor()
                        synchronized(this) {
                            if (epoch == generation) emit(if (status == 0) "done" else "error", id, if (status == 0) "" else "Device speech failed (exit $status)")
                            if (process === child) process = null
                        }
                    } catch (e: Exception) { synchronized(this) { if (epoch == generation) emit("error", id, e.message.orEmpty()) } }
                }
            }
        }
    }
    private fun cancel() {
        generation++
        if (process != null && dispatcher) runCatching { run(listOf("spd-say", "-C")) }
        process?.destroy()
        process = null
    }
    override fun poll(): String? = events.poll()
    @Synchronized override fun close() { cancel(); pending.shutdownNow() }
}
