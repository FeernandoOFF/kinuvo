package kinuvo.ui.session

import kinuvo.app.session.VoiceAudio
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import javax.swing.SwingUtilities
import kotlin.io.encoding.Base64
import kotlin.math.sqrt

/** Windows microphone capture independent of installed SAPI recognizers. All callbacks run on the UI thread. */
internal class DesktopVoiceAudio : VoiceAudio {
    private val format = AudioFormat(16000f, 16, 1, true, false)
    @Volatile private var generation = 0
    @Volatile private var finish = false
    private var line: TargetDataLine? = null
    private var clip: Clip? = null

    override fun authorizeMicrophone(completion: (String?) -> Unit) {
        completion(runCatching { (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).use { it.open(format) }; null }
            .getOrElse { "Allow microphone access for desktop apps and select an audio input" })
    }

    override fun record(untilPause: Boolean, onRecorded: (String) -> Unit, onError: (String) -> Unit) {
        stopRecording()
        val id = generation
        finish = false
        val input = try {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).also { it.open(format); it.start(); line = it }
        } catch (_: Exception) { onError("Cannot open the microphone"); return }
        Thread({
            try {
                val pcm = ByteArrayOutputStream()
                val buffer = ByteArray(3200)
                var voiced = 0
                var quiet = 0
                while (id == generation && !finish && pcm.size() < 960000) {
                    val count = input.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    pcm.write(buffer, 0, count)
                    var energy = 0.0
                    for (i in 0 until count - 1 step 2) {
                        val value = (((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble()) / 32768
                        energy += value * value
                    }
                    if (sqrt(energy / (count / 2)) > 0.006) { voiced += count / 2; quiet = 0 } else quiet += count / 2
                    if (untilPause && voiced >= 1920 && quiet >= 20800 || voiced < 1920 && pcm.size() >= 320000) break
                }
                val wav = ByteArrayOutputStream()
                if (voiced >= 1920) AudioInputStream(ByteArrayInputStream(pcm.toByteArray()), format, (pcm.size() / 2).toLong()).use {
                    AudioSystem.write(it, AudioFileFormat.Type.WAVE, wav)
                }
                SwingUtilities.invokeLater { if (id == generation) { line = null; onRecorded(if (wav.size() == 0) "" else Base64.encode(wav.toByteArray())) } }
            } catch (_: Exception) { SwingUtilities.invokeLater { if (id == generation) onError("Microphone recording failed") } }
            finally { input.close() }
        }, "kinuvo-recording").apply { isDaemon = true; start() }
    }

    override fun finishRecording() { finish = true }
    override fun stopRecording() { generation++; line?.close(); line = null }

    override fun play(base64: String, onDone: () -> Unit, onError: (String) -> Unit) {
        stopPlayback()
        try {
            val playing = AudioSystem.getClip()
            clip = playing
            playing.addLineListener { event -> if (event.type == LineEvent.Type.STOP) SwingUtilities.invokeLater {
                if (clip === playing) { clip = null; playing.close(); onDone() }
            } }
            AudioSystem.getAudioInputStream(ByteArrayInputStream(Base64.decode(base64))).use { playing.open(it) }
            playing.start()
        } catch (_: Exception) { stopPlayback(); onError("Cannot play synthesized audio") }
    }

    override fun stopPlayback() { val previous = clip; clip = null; previous?.close() }
}
