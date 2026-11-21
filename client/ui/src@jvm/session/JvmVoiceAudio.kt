package kinuvo.ui.session

import kinuvo.app.session.VoiceAudio
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

internal val captureFormat get() = AudioFormat(16000f, 16, 1, true, false)

internal class JvmVoiceAudio : VoiceAudio {
    private class Recording {
        @Volatile var active = true
        @Volatile var canceled = false
        @Volatile var line: TargetDataLine? = null
    }
    private class Playback {
        @Volatile var canceled = false
        @Volatile var line: SourceDataLine? = null
    }
    private var recording: Recording? = null
    private var playback: Playback? = null
    override fun authorizeAudio(completion: (String?) -> Unit) = completion(null)
    override fun record(untilPause: Boolean, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) {
        stopRecording()
        val session = Recording().also { recording = it }
        Thread({
            val out = ByteArrayOutputStream()
            try {
                val line = AudioSystem.getTargetDataLine(captureFormat)
                synchronized(session) {
                    session.line = line
                    if (!session.active) return@Thread
                    line.open(captureFormat); line.start()
                }
                val buffer = ByteArray(3200)
                var heard = false
                var silence = 0
                while (session.active && out.size() < 960000) {
                    val n = line.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    if (pcmPeak(buffer, n) > 600) { heard = true; silence = 0 } else silence += n
                    if (untilPause && heard && silence >= 32000) break
                }
                val pcm = out.toByteArray()
                Dispatchers.Main.dispatch(EmptyCoroutineContext) { if (!session.canceled) onAudio(pcm) }
            } catch (e: Exception) {
                Dispatchers.Main.dispatch(EmptyCoroutineContext) {
                    if (!session.canceled) {
                        if (!session.active) onAudio(out.toByteArray())
                        else onError("Cannot record microphone: ${e.message}")
                    }
                }
            } finally { session.line?.close() }
        }, "kinuvo-record").apply { isDaemon = true; start() }
    }
    override fun finishRecording() { recording?.let { synchronized(it) { it.active = false; it.line?.stop(); it.line?.close() } } }
    override fun stopRecording() { recording?.canceled = true; finishRecording(); recording = null }
    override fun play(wav: ByteArray, onDone: () -> Unit, onError: (String) -> Unit) {
        stopPlayback()
        val session = Playback().also { playback = it }
        Thread({
            try {
                AudioSystem.getAudioInputStream(ByteArrayInputStream(wav)).use { audio ->
                    val line = AudioSystem.getSourceDataLine(audio.format)
                    synchronized(session) {
                        session.line = line
                        if (session.canceled) return@Thread
                        line.open(audio.format); line.start()
                    }
                    val buffer = ByteArray(8192)
                    while (!session.canceled) {
                        val n = audio.read(buffer)
                        if (n < 0) break
                        var at = 0
                        while (at < n && !session.canceled) {
                            val written = line.write(buffer, at, n - at)
                            if (written <= 0) break
                            at += written
                        }
                    }
                    if (!session.canceled) line.drain()
                }
                Dispatchers.Main.dispatch(EmptyCoroutineContext) { if (!session.canceled) onDone() }
            } catch (e: Exception) {
                Dispatchers.Main.dispatch(EmptyCoroutineContext) { if (!session.canceled) onError("Cannot play voice audio: ${e.message}") }
            } finally { session.line?.close() }
        }, "kinuvo-playback").apply { isDaemon = true; start() }
    }
    override fun stopPlayback() { playback?.let { synchronized(it) { it.canceled = true; it.line?.stop(); it.line?.flush(); it.line?.close() } }; playback = null }
}

internal fun pcmPeak(pcm: ByteArray, size: Int = pcm.size): Int = (0 until size - 1 step 2).maxOfOrNull {
    kotlin.math.abs(((pcm[it].toInt() and 255) or (pcm[it + 1].toInt() shl 8)).toShort().toInt())
} ?: 0
