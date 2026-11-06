package kinuvo.ui.session

import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig
import kinuvo.app.session.LiveAudio
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

internal interface LiveAudioProcessing {
    fun capture(frame: ByteArray)
    fun playback(frame: ByteArray)
    fun dispose()
}

internal class JvmLiveAudio(
    private val microphone: (AudioFormat) -> TargetDataLine = AudioSystem::getTargetDataLine,
    private val speaker: (AudioFormat) -> SourceDataLine = AudioSystem::getSourceDataLine,
    private val processing: (Int) -> LiveAudioProcessing? = { rate ->
        runCatching {
            val native = echoCanceller()
            val stream = AudioProcessingStreamConfig(rate, 1)
            object : LiveAudioProcessing {
                override fun capture(frame: ByteArray) { native.processStream(frame, stream, stream, frame) }
                override fun playback(frame: ByteArray) { native.processReverseStream(frame, stream, stream, frame) }
                override fun dispose() = native.dispose()
            }
        }.getOrNull()
    },
) : LiveAudio {
    private class Chunk(val pcm: ByteArray, val generation: Long)
    private class Session(val bytesPerMilli: Int) {
        @Volatile var active = true
        @Volatile var generation = 0L
        @Volatile var mic: TargetDataLine? = null
        @Volatile var out: SourceDataLine? = null
        @Volatile var canceller: LiveAudioProcessing? = null
        var threads = emptyList<Thread>()
        val playback = LinkedBlockingQueue<Chunk>()
        var waiting = 0
    }

    @Volatile private var session: Session? = null
    override val echoes get() = session?.canceller == null
    override fun authorizeAudio(completion: (String?) -> Unit) = completion(null)

    @Synchronized
    override fun startLive(sampleRate: Int, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) {
        stopLive()
        val s = Session(sampleRate * 2 / 1000)
        session = s
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val frame = s.bytesPerMilli * 10
        try {
            s.canceller = processing(sampleRate)
            val mic = microphone(format).also { s.mic = it }
            mic.open(format, s.bytesPerMilli * 200)
            mic.start()
            val out = speaker(format).also { s.out = it }
            out.open(format, s.bytesPerMilli * 200)
            out.start()
            val capture = Thread({
                try {
                    val chunk = ByteArray(frame * 10)
                    val heard = ByteArray(frame)
                    while (s.active) {
                        var filled = 0
                        while (filled < chunk.size && s.active) {
                            val n = mic.read(heard, 0, frame)
                            if (!s.active) break
                            if (n < frame) continue
                            s.canceller?.capture(heard)
                            heard.copyInto(chunk, filled)
                            filled += frame
                        }
                        if (s.active) onAudio(chunk.copyOf())
                    }
                } catch (e: Exception) { if (s.active) onError("Call audio failed: ${e.message}") }
            }, "kinuvo-live-microphone")
            val play = Thread({
                try {
                    val far = ByteArray(frame)
                    while (s.active) {
                        val chunk = s.playback.take()
                        var at = 0
                        while (s.active && chunk.generation == s.generation && at < chunk.pcm.size) {
                            val n = minOf(frame, chunk.pcm.size - at)
                            chunk.pcm.copyInto(far, 0, at, at + n)
                            far.fill(0, n)
                            s.canceller?.playback(far)
                            if (!s.active || chunk.generation != s.generation) break
                            val written = out.write(chunk.pcm, at, n)
                            if (written <= 0) break
                            at += written
                        }
                        synchronized(s) {
                            if (chunk.generation == s.generation) s.waiting -= chunk.pcm.size
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) { if (s.active) onError("Call audio failed: ${e.message}") }
            }, "kinuvo-live-speaker")
            s.threads = listOf(capture, play)
            s.threads.forEach { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            stopLive()
            onError(e.message?.let { "Couldn't start call audio: $it" } ?: "The microphone or speaker is unavailable")
        }
    }

    override fun playLive(pcm: ByteArray) {
        val s = session ?: return
        synchronized(s) {
            if (!s.active) return
            s.waiting += pcm.size
            s.playback.add(Chunk(pcm, s.generation))
        }
    }

    override fun queuedMillis(): Int {
        val s = session ?: return 0
        return synchronized(s) {
            val line = s.out ?: return 0
            (s.waiting + line.bufferSize - line.available()) / s.bytesPerMilli
        }
    }

    override fun flushLive() {
        val s = session ?: return
        synchronized(s) {
            s.generation++
            s.playback.clear()
            s.waiting = 0
            s.out?.flush()
        }
    }

    @Synchronized
    override fun stopLive() {
        val s = session ?: return
        session = null
        synchronized(s) {
            s.active = false
            s.generation++
            s.playback.clear()
            s.waiting = 0
        }
        s.threads.forEach { it.interrupt() }
        s.mic?.let { line -> runCatching { line.stop() }; runCatching { line.close() } }
        s.out?.let { line -> runCatching { line.flush() }; runCatching { line.stop() }; runCatching { line.close() } }
        var interrupted = false
        s.threads.filter { it !== Thread.currentThread() }.forEach {
            try { it.join(1_000) } catch (_: InterruptedException) { interrupted = true }
        }
        if (s.threads.none { it.isAlive }) s.canceller?.dispose()
        else System.err.println("Call audio workers did not exit; retaining echo canceller to avoid native use after free")
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal fun echoCanceller() = AudioProcessing().apply {
    applyConfig(AudioProcessingConfig().apply {
        echoCanceller.enabled = true
        noiseSuppression.enabled = true
        highPassFilter.enabled = true
    })
}
