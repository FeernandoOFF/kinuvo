package kinuvo.ui.session

import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertTrue

class EchoCancellerTest {
    @Test
    fun removesTheReplyFromTheMicrophone() {
        val file = File.createTempFile("say", ".wav").apply { deleteOnExit() }
        val process = try {
            ProcessBuilder("say", "-o", file.path, "--data-format=LEI16@24000", "The weather in Paris is sunny and eighteen degrees, with a light wind from the west all afternoon.").start()
        } catch (_: IOException) {
            return
        }
        if (process.waitFor() != 0) return
        val reply = pcm(file)
        val canceller = echoCanceller()
        val stream = AudioProcessingStreamConfig(24_000, 1)
        val frame = 480
        val delay = frame * 4
        var before = 0.0; var after = 0.0
        for (at in 0 until reply.size - frame - delay step frame) {
            val far = reply.copyOfRange(at + delay, at + delay + frame)
            val near = ByteArray(frame).also { echo -> for (i in 0 until frame / 2) sample(echo, i, sample(reply, (at + i * 2) / 2) / 2) }
            canceller.processReverseStream(far, stream, stream, far)
            val heard = near.copyOf()
            canceller.processStream(heard, stream, stream, heard)
            if (at > reply.size / 2) { before += energy(near); after += energy(heard) }
        }
        canceller.dispose()
        assertTrue(after < before / 10, "echo energy before $before after $after")
    }

    private fun pcm(file: File): ByteArray {
        RandomAccessFile(file, "r").use { wav ->
            var at = 12L
            while (true) {
                wav.seek(at)
                val id = ByteArray(4).also { wav.readFully(it) }.decodeToString()
                val size = Integer.reverseBytes(wav.readInt()).toLong()
                if (id == "data") return ByteArray(size.toInt()).also { wav.readFully(it) }
                at += 8 + size
            }
        }
    }

    private fun sample(pcm: ByteArray, i: Int) = (pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)
    private fun sample(pcm: ByteArray, i: Int, v: Int) { pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte() }
    private fun energy(pcm: ByteArray) = (0 until pcm.size / 2).sumOf { sample(pcm, it).toDouble().let { s -> s * s } }
}
