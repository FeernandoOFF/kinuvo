package kinuvo.app.session

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class VoiceCue(private val notes: List<Pair<Int, Int>>) {
    Connecting(listOf(330 to 160)),
    Ready(listOf(523 to 125, 784 to 125)),
    Ended(listOf(523 to 125, 349 to 125));

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
                val sample = (sin(2 * PI * frequency * i / sampleRate) * envelope * 0.08 * Short.MAX_VALUE).toInt()
                pcm[offset++] = sample.toByte()
                pcm[offset++] = (sample shr 8).toByte()
            }
        }
        return pcm
    }
}
