package kinuvo.ui.session

import kinuvo.app.session.LiveAudio
import kinuvo.app.session.LiveCallPeer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

internal class JvmLiveAudio(
    private val speaker: (AudioFormat) -> SourceDataLine = AudioSystem::getSourceDataLine,
    private val callPeer: () -> LiveCallPeer = ::JvmLiveCallPeer,
) : LiveAudio {
    override fun authorizeAudio(completion: (String?) -> Unit) = completion(null)
    override val callPeerSupported get() = true
    override fun createCallPeer(): LiveCallPeer = callPeer()

    private val cueLock = Any()
    private var cue: SourceDataLine? = null
    private var cueGeneration = 0

    override fun playCue(pcm: ByteArray, sampleRate: Int) {
        stopCue()
        val epoch = synchronized(cueLock) { cueGeneration }
        Thread({
            var line: SourceDataLine? = null
            try {
                synchronized(cueLock) {
                    if (epoch != cueGeneration) return@Thread
                    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                    line = speaker(format).also { cue = it; it.open(format); it.start() }
                }
                line?.write(pcm, 0, pcm.size)
                line?.drain()
            } catch (_: Exception) {
            } finally {
                synchronized(cueLock) {
                    line?.let { runCatching { it.close() } }
                    if (cue === line) cue = null
                }
            }
        }, "kinuvo-voice-cue").apply { isDaemon = true; start() }
    }

    override fun stopCue() = synchronized(cueLock) {
        cueGeneration++
        cue?.let { runCatching { it.stop(); it.flush(); it.close() } }
        cue = null
    }
}
