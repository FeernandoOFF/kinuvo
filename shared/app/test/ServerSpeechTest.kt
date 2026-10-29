package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSpeechTest {
    private class AudioDevice : SpeechEngine, VoiceAudio {
        var local = 0
        var recorded: (ByteArray) -> Unit = {}
        var played = byteArrayOf()
        var holdPlayback = false
        var playbackStops = 0
        override fun authorize(completion: (String?) -> Unit) { local++; completion(null) }
        override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) { local++ }
        override fun speak(text: String, onDone: () -> Unit) { local++; onDone() }
        override fun stopSpeaking() {}
        override fun stop() {}
        override fun authorizeAudio(completion: (String?) -> Unit) = completion(null)
        override fun record(untilPause: Boolean, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) { recorded = onAudio }
        override fun finishRecording() { recorded(byteArrayOf(1, 0)) }
        override fun stopRecording() {}
        override fun play(wav: ByteArray, onDone: () -> Unit, onError: (String) -> Unit) { played = wav; if (!holdPlayback) onDone() }
        override fun stopPlayback() { playbackStops++ }
    }
    @Test fun configuredServerRoutesAudioAndRefreshesAtEachStart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        var setup = VoiceSetup(transcriber = true, synthesizer = true)
        var drop: String? = null
        val calls = mutableListOf<String>()
        val connection = object : kinuvo.protocol.FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val r = frame as Frame.Request
                calls += r.method
                if (r.method == drop) return
                val payload = when (r.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.VoiceSetup.name -> ProtocolJson.encodeToJsonElement(setup)
                    Api.Transcribe.name -> JsonPrimitive("Server words")
                    Api.Synthesize.name -> ProtocolJson.encodeToJsonElement(SpeechAudio("AQI="))
                    else -> buildJsonObject {}
                }
                frames.send(Frame.Response(r.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { connection }, dispatcher = StandardTestDispatcher(testScheduler))
        val prefs = MemoryPreferences()
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(prefs, reader))
        try {
            client.connect(); server.open(client, "local:test")
            val device = AudioDevice()
            val speech = ServerSpeech(device, server, StandardTestDispatcher(testScheduler))
            val authorized = CompletableDeferred<String?>()
            speech.authorize { authorized.complete(it) }
            assertNull(authorized.await())
            val words = CompletableDeferred<String>()
            speech.listen(false, {}, { error("Old finish callback") }, { error(it) })
            speech.finish("", { words.complete(it) }, { error(it) })
            assertEquals("Server words", words.await())
            val spoken = CompletableDeferred<Unit>()
            speech.speak("Reply", { spoken.complete(Unit) }, { error(it) })
            spoken.await()
            assertContentEquals(byteArrayOf(1, 2), device.played)
            assertEquals(0, device.local)
            val staleCapture = device.recorded
            speech.stop()
            val before = calls.count { it == Api.Transcribe.name }
            staleCapture(byteArrayOf(1, 0))
            runCurrent()
            assertEquals(before, calls.count { it == Api.Transcribe.name })
            setup = VoiceSetup()
            val refreshed = CompletableDeferred<Unit>()
            speech.authorize { assertNull(it); refreshed.complete(Unit) }
            refreshed.await()
            speech.listen(false, {}, {}, {})
            assertEquals(2, device.local)
            assertEquals(2, calls.count { it == Api.VoiceSetup.name })
            speech.stop()
            setup = VoiceSetup(transcriber = true, synthesizer = true)
            speech.authorize { assertNull(it) }
            runCurrent()
            drop = Api.Transcribe.name
            var failure: String? = null
            speech.listen(false, {}, { error("Timed-out transcription completed") }, { failure = it })
            device.recorded(byteArrayOf(1, 0))
            runCurrent()
            advanceTimeBy(135_001)
            runCurrent()
            assertTrue(failure.orEmpty().contains("Server transcription timed out"))
            assertTrue(Api.CancelVoice.name in calls)
            assertEquals(2, device.local, "Server timeouts must not fall back to Device speech")
            speech.stop()
            drop = Api.Synthesize.name
            speech.authorize { assertNull(it) }
            runCurrent()
            failure = null
            speech.speak("Reply", { error("Timed-out synthesis completed") }, { failure = it })
            runCurrent()
            advanceTimeBy(135_001)
            runCurrent()
            assertTrue(failure.orEmpty().contains("Server synthesis timed out"))
            speech.stop()
            drop = null
            speech.authorize { assertNull(it) }
            runCurrent()
            device.holdPlayback = true
            val stops = device.playbackStops
            failure = null
            speech.speak("Reply", { error("Stalled playback completed") }, { failure = it })
            runCurrent()
            advanceTimeBy(130_001)
            runCurrent()
            assertTrue(failure.orEmpty().contains("Voice playback timed out"))
            assertTrue(device.playbackStops > stops)
            speech.stop()
            drop = Api.VoiceSetup.name
            var authorizationFailure: String? = null
            speech.authorize { authorizationFailure = it }
            runCurrent()
            advanceTimeBy(15_001)
            runCurrent()
            assertTrue(authorizationFailure.orEmpty().contains("Loading Server voice settings timed out"))
            speech.stop()
        } finally { server.close(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun recordingsUploadAsWavAndRepliesSynthesizeInChunks() {
        val clip = wav(byteArrayOf(1, 2, 3, 4))
        assertEquals("RIFF", clip.copyOfRange(0, 4).decodeToString())
        assertEquals(48, clip.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), clip.copyOfRange(44, 48))
        val chunks = speechChunks("word ".repeat(200))
        assertTrue(chunks.size > 1 && chunks.all { it.length <= 240 })
    }
}
