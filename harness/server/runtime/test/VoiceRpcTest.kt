package kinuvo.harness.server

import kinuvo.harness.core.voice.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.*

class VoiceRpcTest {
    @Test fun cancellingClientVoiceCallsStopsProvidersAndReleasesAFullRequestQuota() = runTest {
        val entered = Channel<String>(Channel.UNLIMITED)
        val stopped = Channel<String>(Channel.UNLIMITED)
        suspend fun provider(part: String): Nothing {
            entered.send(part)
            try { awaitCancellation() } finally { stopped.trySend(part) }
        }
        val voice = VoiceProviders(transcriber = { provider("transcription") }, synthesizer = { provider("synthesis") })
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val serverConnection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { responses.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val clientConnection = object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) { requests.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val peers = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun management(token: String) = false
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
        }
        val server = ServerRuntime(RuntimeFixture().runtime(voice = voice), peers, security = ConnectionSecurity(concurrentRequests = 1))
        val client = KinuvoClient("local:test", "test", connection = { clientConnection })
        server.start()
        val serving = launch { server.serve(serverConnection) }
        try {
            client.connect()
            repeat(12) { turn ->
                val part = if (turn % 2 == 0) "transcription" else "synthesis"
                val call = launch {
                    if (turn % 2 == 0) client.call(Api.Transcribe, TranscribeParams("AA=="))
                    else client.call(Api.Synthesize, SynthesizeParams("hello"))
                }
                assertEquals(part, entered.receive())
                call.cancelAndJoin()
                assertEquals(part, stopped.receive())
                client.call(Api.Presence, PresenceParams(true))
            }
        } finally { client.close(); server.close(); serving.cancelAndJoin() }
    }

    @Test fun pairedNonManagementDevicesCanUseAdvertisedVoiceParts() = runTest {
        val wav = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVE".encodeToByteArray() + ByteArray(34)
        var transcriptions = 0
        var syntheses = 0
        val voice = VoiceProviders(transcriber = {
            transcriptions++
            assertContentEquals(wav, it.bytes)
            "Hello"
        }, synthesizer = {
            syntheses++
            assertEquals("Reply", it)
            Audio(wav, AudioFormat.Wav)
        })
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { responses.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val peers = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun management(token: String) = false
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
        }
        val server = ServerRuntime(RuntimeFixture().runtime(voice = voice), peers)
        var id = 0
        suspend fun <P, R> call(method: Method<P, R>, params: P): Frame.Response {
            val requestId = (++id).toString()
            requests.send(Frame.Request(requestId, method.name, ProtocolJson.encodeToJsonElement(method.params, params)))
            while (true) {
                val frame = responses.receive()
                if (frame is Frame.Response && frame.id == requestId) return frame
            }
        }
        server.start()
        val serving = launch { server.serve(connection) }
        try {
            val connected = call(Api.Connect, ConnectParams("test"))
            assertTrue(connected.ok)
            assertEquals(VoiceSetup(transcriber = true, synthesizer = true), ProtocolJson.decodeFromJsonElement(Api.Connect.result, connected.payload).capabilities.voice)
            val transcript = call(Api.Transcribe, TranscribeParams(Base64.encode(wav)))
            assertTrue(transcript.ok)
            assertEquals("Hello", ProtocolJson.decodeFromJsonElement(Api.Transcribe.result, transcript.payload))
            val speech = call(Api.Synthesize, SynthesizeParams("Reply"))
            assertTrue(speech.ok)
            assertContentEquals(wav, Base64.decode(ProtocolJson.decodeFromJsonElement(Api.Synthesize.result, speech.payload).base64))
            assertFalse(call(Api.Transcribe, TranscribeParams("x".repeat((MAX_RECORDING_BYTES + 2) / 3 * 4 + 1))).ok)
            assertFalse(call(Api.Transcribe, TranscribeParams("invalid-base64")).ok)
            assertEquals(1, transcriptions)
            assertEquals(1, syntheses)
        } finally { server.close(); serving.cancelAndJoin() }
    }
}
