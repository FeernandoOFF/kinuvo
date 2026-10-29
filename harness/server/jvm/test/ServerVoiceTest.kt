package kinuvo.harness.server

import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ServerVoiceTest {
    @Test fun realServerDownloadsCancelsAndRunsNativeInference(): Unit = runBlocking {
        if (System.getenv("KINUVO_TEST_SERVER_VOICE_INFERENCE") != "1") return@runBlocking
        val home = Files.createTempDirectory("server-voice-inference").toFile()
        val jar = System.getenv("KINUVO_TEST_VOICE_JAR")
        val server = if (jar == null) HarnessServer(Harness(Path(home.path))) else null
        val process = jar?.let { ProcessBuilder(java.io.File(System.getProperty("java.home"), "bin/java").path, "-jar", it, "serve", "--host", "127.0.0.1", "--port", "0")
            .apply { environment()["KINUVO_HOME"] = home.path; environment().remove("KINUVO_EMBEDDED_PORT"); environment().remove("KINUVO_VOICE_WORKER") }
            .redirectErrorStream(true).redirectOutput(home.resolve("server.log")).start() }
        val local = if (server != null) LocalServer("127.0.0.1", server.start("127.0.0.1", 0, schedule = false), server.auth.sessionToken(), ProcessHandle.current().pid())
            else withTimeout(30_000) {
                var result: LocalServer? = null
                while (result == null) { check(process!!.isAlive) { home.resolve("server.log").readText() }; result = LocalServer.read(Path(home.path)); delay(50) }
                result
            }
        val port = local.port
        val owner = KinuvoClient("ws://127.0.0.1:$port/ws", "owner", token = local.token)
        var paired: KinuvoClient? = null
        try {
            owner.connect()
            owner.call(Api.VoiceDownload, "whisper-tiny")
            withTimeout(60_000) { while (owner.call(Api.VoiceModels, Unit).downloaded < 65536) delay(50) }
            owner.call(Api.VoiceCancelDownload, Unit)
            withTimeout(15_000) { while (owner.call(Api.VoiceModels, Unit).downloading != null) delay(50) }
            assertFalse(home.resolve("server-voice/models/whisper-tiny.part").exists())
            for (id in listOf("whisper-tiny", "supertonic-3")) {
                owner.call(Api.VoiceDownload, id)
                withTimeout(300_000) { while (true) {
                    val state = owner.call(Api.VoiceModels, Unit)
                    assertNull(state.error)
                    if (state.downloading == null) { assertTrue(id in state.installed); break }
                    delay(200)
                } }
            }
            owner.call(Api.VoiceSelect, VoiceSelection(VoiceModelKind.Transcription, "whisper-tiny"))
            owner.call(Api.VoiceSelect, VoiceSelection(VoiceModelKind.Synthesis, "supertonic-3"))
            val code = owner.call(Api.Pairing, PairingParams())
            paired = KinuvoClient("ws://127.0.0.1:$port/ws", "paired", pairingCode = code.code)
            assertTrue(paired.connect().capabilities.voice.transcriber)
            val clip = paired.call(Api.Synthesize, SynthesizeParams("Hello world. This is a test of the voice system."))
            val wav = kotlin.io.encoding.Base64.decode(clip.base64)
            assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
            val buffer = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rate = buffer.getInt(24)
            val samples = (wav.size - 44) / 2
            val pcm = java.nio.ByteBuffer.allocate((samples.toLong() * 16000 / rate * 2).toInt()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (n in 0 until pcm.capacity() / 2) pcm.putShort(buffer.getShort(44 + (n.toLong() * rate / 16000).toInt() * 2))
            val recording = java.nio.ByteBuffer.allocate(44 + pcm.capacity()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
            val text = paired.call(Api.Transcribe, TranscribeParams(kotlin.io.encoding.Base64.encode(recording.array())))
            assertTrue("hello" in text.lowercase() && "world" in text.lowercase(), text)
            owner.call(Api.VoiceRemove, "whisper-tiny")
            assertFalse(paired.call(Api.VoiceSetup, Unit).transcriber)
        } finally { paired?.close(); owner.close(); server?.stop(); process?.destroy(); process?.waitFor(); home.deleteRecursively() }
    }

    @Test fun serverOwnsSettingsAndOrdinaryDevicesOnlyGetInference() = runBlocking {
        val home = Files.createTempDirectory("server-voice-test").toFile()
        val server = HarnessServer(Harness(Path(home.path)))
        val port = server.start("127.0.0.1", 0, schedule = false)
        val address = "ws://127.0.0.1:$port/ws"
        val owner = KinuvoClient(address, "owner", token = server.auth.sessionToken())
        val second = KinuvoClient(address, "second", token = server.auth.sessionToken())
        var ordinary: KinuvoClient? = null
        try {
            owner.connect(); second.connect()
            owner.call(Api.VoiceLanguage, "fr")
            assertEquals("fr", second.call(Api.VoiceModels, Unit).language)
            assertEquals(setOf("whisper-tiny", "whisper-base", "supertonic-3"), owner.call(Api.VoiceModels, Unit).models.map { it.id }.toSet())
            assertFails { owner.call(Api.VoiceSelect, VoiceSelection(VoiceModelKind.Transcription, "whisper-tiny")) }
            val pairing = owner.call(Api.Pairing, PairingParams())
            ordinary = KinuvoClient(address, "ordinary", pairingCode = pairing.code)
            ordinary.connect()
            assertEquals(VoiceSetup(), ordinary.call(Api.VoiceSetup, Unit))
            assertFails { ordinary.call(Api.VoiceModels, Unit) }
            assertFails { ordinary.call(Api.VoiceLanguage, "en") }
            assertFails { ordinary.call(Api.VoiceDownload, "whisper-tiny") }
            assertFails { ordinary.call(Api.VoiceCancelDownload, Unit) }
            assertFails { ordinary.call(Api.VoiceRemove, "whisper-tiny") }
            assertFails { ordinary.call(Api.VoiceSelect, VoiceSelection(VoiceModelKind.Transcription)) }
            assertFails { ordinary.call(Api.Transcribe, TranscribeParams("")) }
        } finally { ordinary?.close(); second.close(); owner.close(); server.stop() }
        val restarted = DownloadedVoice(home)
        try { assertEquals("fr", restarted.state().language) } finally { restarted.close(); home.deleteRecursively() }
    }

    @Test fun downloadedModelsReadWavRecordings() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
        assertContentEquals(pcm, wavPcm16(wav))
        assertFails { wavPcm16(wav.copyOf().also { it[24] = 0x44 }) }
    }
}
