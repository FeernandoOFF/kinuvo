package kinuvo.server

import kinuvo.server.plugin.Audio
import kinuvo.server.plugin.AudioFormat
import kinuvo.server.plugin.SpeechToSpeech
import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceInput
import kinuvo.server.plugin.VoiceOutput
import kinuvo.server.plugin.VoiceProviders
import kinuvo.server.plugin.AgentProgress
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.*
import kinuvo.server.execution.ExecutionRegistry
import kotlinx.serialization.json.JsonElement
import kinuvo.testing.harness.scriptedModel
import kinuvo.server.devices.*
import kinuvo.server.voice.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.*

class VoiceRpcTest {
    @Test fun liveCallsDelegateThroughExternalDispatch() = runTest {
        val registry = ExecutionRegistry().also {
            it.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
            it.register(AgentRegistration("external", "Agent", "c", cwd = "/tmp"))
        }
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession = object : ExecutionSession {
                override val capabilities = ExecutionCapabilities()
                override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                    assertTrue(input.text.startsWith(liveVoiceInstructions))
                    assertTrue(input.text.endsWith("\n\ntask"))
                    scope.launch {
                        delay(200)
                        state.boundSink().commit(listOf(SessionEntry.AssistantMessage("external reply", "reply")))
                        state.boundSink().publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
                    }
                    return ExecutionAcceptance("accepted")
                }
                override suspend fun cancel() {}
                override suspend fun answer(requestId: String, answer: InteractionAnswer) {}
            }
        }
        val voice = VoiceProviders(speechToSpeech = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                output(VoiceOutput.Transcript("reply", askAgent("task") {}, false))
            }
        })
        val fixture = RuntimeFixture(voice = voice)
        fixture.settings.liveVoiceEnabled = true
        val server = ServerRuntime(fixture.runtime(), voicePeers(), executionRegistry = registry, executionFactory = factory)
        val device = VoiceDevice()
        server.start()
        val serving = launch { server.serve(device.connection) }
        try {
            device.call(Api.Connect, ConnectParams("test"))
            val session = device.call(Api.CreateSession, AgentRef("external"))
            val call = device.call(Api.LiveVoiceStart, LiveVoiceParams("external", session.id))
            assertEquals(LiveVoiceOutput.Transcript(call.callId, "reply", "external reply", false), device.outputs.receive())
            device.outputs.receive()
            val entries = fixture.transcripts.entries("external", session.id)
            assertTrue(entries.filterIsInstance<SessionEntry.UserMessage>().single().voice)
            assertEquals("external reply", entries.filterIsInstance<SessionEntry.VoiceCall>().single().turns.single().text)
        } finally { server.close(); serving.cancelAndJoin() }
    }

    @Test fun liveCallsDelegateVoiceMessagesToTheSessionAndReturnRepliesAndFailures() = runTest {
        var fail = false
        var runs = 0
        val voice = VoiceProviders(speechToSpeech = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                val text = try { askAgent("some task") {} } catch (e: IllegalStateException) { "failure: ${e.message}" }
                output(VoiceOutput.Transcript("reply", text, false))
            }
        })
        val fixture = RuntimeFixture(voice = voice)
        fixture.settings.liveVoiceEnabled = true
        val harness = fixture.runtime(scriptedModel { _, _ ->
            runs++
            if (fail) flow { error("Model failed") }
            else flowOf(StreamFrame.TextDelta("Known reply"), StreamFrame.End())
        })
        val session = harness.newSession(fixture.agent)
        val server = ServerRuntime(harness, voicePeers())
        val device = VoiceDevice()
        server.start()
        val serving = launch { server.serve(device.connection) }
        try {
            device.call(Api.Connect, ConnectParams("test"))
            for (failure in listOf(false, true)) {
                fail = failure
                val call = device.call(Api.LiveVoiceStart, LiveVoiceParams(fixture.agent.id, session.id))
                assertEquals(LiveVoiceOutput.Transcript(call.callId, "reply",
                    if (failure) "failure: The Agent could not finish the request" else "Known reply", false), device.outputs.receive())
                assertEquals(LiveVoiceOutput.Ended(call.callId), device.outputs.receive())
            }
            assertEquals(2, fixture.transcripts.entries(fixture.agent.id, session.id).filterIsInstance<SessionEntry.VoiceCall>().size)
            assertEquals(2, runs)
            val messages = fixture.transcripts.entries(fixture.agent.id, session.id).filterIsInstance<SessionEntry.UserMessage>()
            assertEquals(2, messages.size)
            assertTrue(messages.all { it.text == "some task" && it.voice })
        } finally { server.close(); serving.cancelAndJoin() }
    }

    @Test fun liveCallOutputsAndInputsAreIsolatedToTheStartingConnection() = runTest {
        val received = Channel<VoiceInput>(Channel.UNLIMITED)
        val voice = VoiceProviders(speechToSpeech = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                output(VoiceOutput.Ready)
                for (value in input) {
                    received.send(value)
                    output(VoiceOutput.Listening)
                }
            }
        })
        val fixture = RuntimeFixture(voice = voice)
        fixture.settings.liveVoiceEnabled = true
        val harness = fixture.runtime()
        val session = harness.newSession(fixture.agent)
        val server = ServerRuntime(harness, voicePeers())
        val a = VoiceDevice()
        val b = VoiceDevice()
        server.start()
        val servingA = launch { server.serve(a.connection) }
        val servingB = launch { server.serve(b.connection) }
        try {
            a.call(Api.Connect, ConnectParams("a"))
            b.call(Api.Connect, ConnectParams("b"))
            val call = a.call(Api.LiveVoiceStart, LiveVoiceParams(fixture.agent.id, session.id))
            assertEquals(LiveVoiceOutput.Ready(call.callId), a.outputs.receive())
            b.emit(LiveVoiceInput.Pause(call.callId))
            b.call(Api.Presence, PresenceParams(true))
            a.emit(LiveVoiceInput.Resume(call.callId))
            assertEquals(VoiceInput.Resume, received.receive())
            assertEquals(LiveVoiceOutput.Listening(call.callId), a.outputs.receive())
            a.call(Api.LiveVoiceEnd, LiveVoiceRef(call.callId))
            assertEquals(LiveVoiceOutput.Ended(call.callId), a.outputs.receive())
            b.call(Api.Presence, PresenceParams(true))
            assertTrue(received.tryReceive().isFailure)
            assertTrue(a.outputs.tryReceive().isFailure)
            assertTrue(b.outputs.tryReceive().isFailure)
        } finally { server.close(); servingA.cancelAndJoin(); servingB.cancelAndJoin() }
    }

    @Test fun liveAudioIsDroppedWhenTheDeviceFallsBehindButOtherEventsArrive() = runTest {
        val flooded = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val voice = VoiceProviders(speechToSpeech = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                started.await()
                repeat(2_000) { output(VoiceOutput.Audio("reply", ByteArray(1_920))) }
                output(VoiceOutput.Transcript("reply", "Hello there", false, final = false))
                output(VoiceOutput.Transcript("reply", "Hello there.", false))
                output(VoiceOutput.Done("reply"))
                flooded.complete(Unit)
                for (value in input) Unit
            }
        })
        val fixture = RuntimeFixture(voice = voice)
        fixture.settings.liveVoiceEnabled = true
        val harness = fixture.runtime()
        val session = harness.newSession(fixture.agent)
        var awake = 0
        val server = ServerRuntime(harness, voicePeers(), keepAwake = { awake++; AutoCloseable { awake-- } })
        val stalled = CompletableDeferred<Unit>()
        val device = VoiceDevice(stalled)
        server.start()
        val serving = launch { server.serve(device.connection) }
        try {
            device.call(Api.Connect, ConnectParams("test"))
            val call = device.call(Api.LiveVoiceStart, LiveVoiceParams(fixture.agent.id, session.id))
            started.complete(Unit)
            flooded.await()
            assertEquals(1, awake)
            stalled.complete(Unit)
            val events = mutableListOf<LiveVoiceOutput>()
            while (events.lastOrNull() !is LiveVoiceOutput.Done) events += device.outputs.receive()
            val audio = events.count { it is LiveVoiceOutput.Audio }
            assertTrue(audio in 125..126, "$audio audio packets")
            assertEquals(listOf(
                LiveVoiceOutput.Transcript(call.callId, "reply", "Hello there", false, final = false),
                LiveVoiceOutput.Transcript(call.callId, "reply", "Hello there.", false),
                LiveVoiceOutput.Done(call.callId, "reply"),
            ), events.filter { it !is LiveVoiceOutput.Audio })
            device.call(Api.LiveVoiceEnd, LiveVoiceRef(call.callId))
            assertEquals(LiveVoiceOutput.Ended(call.callId), device.outputs.receive())
            assertEquals(0, awake)
            assertEquals(listOf("Hello there."), fixture.transcripts.entries(fixture.agent.id, session.id).filterIsInstance<SessionEntry.VoiceCall>().single().turns.map { it.text })
        } finally { server.close(); serving.cancelAndJoin() }
    }

    @Test fun liveCallsRouteDeviceEventsAndEnforceTheirOwnBudget() = runTest {
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val received = Channel<VoiceInput>(Channel.UNLIMITED)
        val stopped = Channel<Unit>(Channel.UNLIMITED)
        val voice = VoiceProviders(speechToSpeech = object : SpeechToSpeech {
            override val input = AudioFormat.Pcm16(24000)
            override val output = AudioFormat.Pcm16(24000)
            override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
                entered.send(Unit)
                try {
                    output(VoiceOutput.Ready)
                    for (value in input) received.send(value)
                } finally { stopped.trySend(Unit) }
            }
        })
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
        val fixture = RuntimeFixture(voice = voice)
        val harness = fixture.runtime()
        val session = harness.newSession(fixture.agent)
        val server = ServerRuntime(harness, peers, security = ConnectionSecurity(liveVoiceInputsPerMinute = 2))
        val client = KinuvoClient("local:test", "test", connection = { clientConnection })
        server.start()
        val serving = launch { server.serve(serverConnection) }
        try {
            client.connect()
            assertFails { client.call(Api.LiveVoiceStart, LiveVoiceParams(fixture.agent.id, session.id)) }
            fixture.settings.liveVoiceEnabled = true
            val call = client.call(Api.LiveVoiceStart, LiveVoiceParams(fixture.agent.id, session.id))
            entered.receive()
            assertFails { client.call(Api.LiveVoiceStart, LiveVoiceParams(fixture.agent.id, session.id)) }
            client.emit(Api.LiveVoiceInput, LiveVoiceInput.Pause("unknown"))
            client.emit(Api.LiveVoiceInput, LiveVoiceInput.Audio(call.callId, "AQACAA=="))
            assertContentEquals(byteArrayOf(1, 0, 2, 0), (received.receive() as VoiceInput.Audio).pcm)
            client.emit(Api.LiveVoiceInput, LiveVoiceInput.Resume(call.callId))
            stopped.receive()
        } finally { client.close(); server.close(); serving.cancelAndJoin() }
    }

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
        val server = ServerRuntime(RuntimeFixture(voice = voice).runtime(), peers, security = ConnectionSecurity(concurrentRequests = 1))
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
        val server = ServerRuntime(RuntimeFixture(voice = voice).runtime(), peers)
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

private fun voicePeers() = object : PeerRegistry {
    override fun authenticate(device: String, token: String?, pairingCode: String?) = device
    override fun management(token: String) = false
    override fun toolDevices() = emptyList<ToolDevice>()
    override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
}

/** Holds its first live audio until [stalled] completes, like a Device that has fallen behind. */
private class VoiceDevice(private val stalled: CompletableDeferred<Unit>? = null) {
    private val requests = Channel<Frame>(Channel.UNLIMITED)
    private val responses = Channel<Frame.Response>(Channel.UNLIMITED)
    val outputs = Channel<LiveVoiceOutput>(Channel.UNLIMITED)
    private var id = 0
    val connection = object : FrameConnection {
        override val incoming = requests.receiveAsFlow()
        override suspend fun send(frame: Frame) {
            when {
                frame is Frame.Response -> responses.send(frame)
                frame is Frame.Event && frame.event == Api.LiveVoiceOutput.name -> {
                    val output = ProtocolJson.decodeFromJsonElement(Api.LiveVoiceOutput.payload, frame.payload)
                    outputs.send(output)
                    if (output is LiveVoiceOutput.Audio) stalled?.await()
                }
            }
        }
        override suspend fun close() { requests.close(); responses.close(); outputs.close() }
    }
    suspend fun <P, R> call(method: Method<P, R>, params: P): R {
        val requestId = (++id).toString()
        requests.send(Frame.Request(requestId, method.name, ProtocolJson.encodeToJsonElement(method.params, params)))
        val response = responses.receive()
        assertEquals(requestId, response.id)
        assertTrue(response.ok, response.toString())
        return ProtocolJson.decodeFromJsonElement(method.result, response.payload)
    }
    suspend fun emit(value: LiveVoiceInput) {
        requests.send(Frame.Event(Api.LiveVoiceInput.name, ProtocolJson.encodeToJsonElement(Api.LiveVoiceInput.payload, value), 0))
    }
}
