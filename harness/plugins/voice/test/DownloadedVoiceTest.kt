package kinuvo.harness.voice

import kotlin.test.*

class DownloadedVoiceTest {
    @Test fun downloadedModelsReadWavRecordings() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
        assertContentEquals(pcm, wavPcm16(wav))
        assertFails { wavPcm16(wav.copyOf().also { it[24] = 0x44 }) }
    }
}
