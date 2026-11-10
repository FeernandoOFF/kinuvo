package kinuvo.harness.codex

import kinuvo.server.plugin.VoiceOutput
import java.io.ByteArrayOutputStream

/**
 * Turns the call's 10 ms frames into replies. A reply is relayed whole, its pauses included, until it has been quiet
 * for a while after its text is done. The RTP track and the event channel race, so frames heard before a reply starts
 * wait in a short buffer and the reply begins from its first sound. Sound that outlasts the buffer without a turn is
 * relayed under its own item.
 */
internal class ReplyAudio(
    private val packetBytes: Int = 48 * 2 * 40,
    private val trailingFrames: Int = 30,
    private val recentFrames: Int = 100,
) {
    private var item: String? = null
    private var interrupted = false
    private var ending = false
    private var quiet = 0
    private var announced = false
    private var unannounced = 0
    private val packet = ByteArrayOutputStream()
    private val recent = ArrayDeque<ByteArray>()

    fun started(turn: String): List<VoiceOutput> = buildList {
        interrupted = false
        send()
        start(turn, announced = true)
    }

    fun interrupt(turn: String? = null): List<VoiceOutput> {
        if (turn != null && turn != item) return emptyList()
        val active = !interrupted
        interrupted = true
        item = null
        packet.reset()
        recent.clear()
        return if (active) listOf(VoiceOutput.Interrupted) else emptyList()
    }

    /** The reply's text is done; its audio can still be streaming in. */
    fun textDone() { if (item != null) { ending = true; quiet = 0 } }

    fun heard(pcm: ByteArray): List<VoiceOutput> = buildList { heard(pcm) }

    private fun MutableList<VoiceOutput>.heard(pcm: ByteArray) {
        if (interrupted) return
        val current = item
        if (current == null) {
            recent.addLast(pcm)
            if (recent.size <= recentFrames) return
            if (silent(recent.first())) recent.removeFirst()
            else start("audio-${++unannounced}", announced = false)
            return
        }
        if (silent(pcm)) quiet++ else quiet = 0
        packet.write(pcm)
        if (packet.size() >= packetBytes) send()
        if (ending && quiet >= trailingFrames) {
            send()
            item = null
            if (!announced) add(VoiceOutput.Done(current))
        }
    }

    private fun MutableList<VoiceOutput>.start(turn: String, announced: Boolean) {
        item = turn; this@ReplyAudio.announced = announced; ending = !announced; quiet = 0
        val waiting = recent.dropWhile(::silent)
        recent.clear()
        for (pcm in waiting) heard(pcm)
    }

    private fun MutableList<VoiceOutput>.send() {
        val current = item
        if (current != null && packet.size() > 0) add(VoiceOutput.Audio(current, packet.toByteArray()))
        packet.reset()
    }

    private fun silent(pcm: ByteArray) = pcm.all { it == 0.toByte() }
}
