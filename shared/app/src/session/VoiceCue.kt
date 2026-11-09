package kinuvo.app.session

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class VoiceCue(val notes: List<Pair<Int, Int>>, val gain: Double = 0.08) {
    Connecting(listOf(330 to 160)),
    Ready(listOf(523 to 125, 784 to 125)),
    Ended(listOf(523 to 125, 349 to 125)),
    /** A soft, low pulse repeated while the Agent works. */
    Thinking(listOf(220 to 180, 262 to 220), gain = 0.035),
    /** The Agent needs the user, e.g. for an Approval. */
    Attention(listOf(659 to 90, 880 to 90, 1047 to 140)),
    Muted(listOf(440 to 80, 330 to 100), gain = 0.06),
    Unmuted(listOf(330 to 80, 440 to 100), gain = 0.06),
    Interrupt(listOf(587 to 60), gain = 0.05);

    fun pcm(sampleRate: Int): ByteArray {
        require(sampleRate > 0)
        val pcm = ByteArray(notes.sumOf { (_, ms) -> sampleRate * ms / 1000 } * 2)
        var offset = 0
        for ((frequency, ms) in notes) {
            val count = sampleRate * ms / 1000
            val ramp = (sampleRate * 20 / 1000).coerceAtLeast(1)
            repeat(count) { i ->
                val edge = minOf(i, count - 1 - i, ramp).toDouble() / ramp
                val envelope = (1 - cos(PI * edge)) / 2
                val sample = (sin(2 * PI * frequency * i / sampleRate) * envelope * gain * Short.MAX_VALUE).toInt()
                pcm[offset++] = sample.toByte()
                pcm[offset++] = (sample shr 8).toByte()
            }
        }
        return pcm
    }

    fun millis() = notes.sumOf { it.second }
}
