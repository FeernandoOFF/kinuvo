package kinuvo.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.*
import kinuvo.server.execution.ExecutionRegistry
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.voice.*
import kinuvo.server.devices.PeerRegistry
import kinuvo.server.plugin.LiveCall
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.LiveCallException
import kinuvo.server.plugin.LiveCallProvider
import kinuvo.server.plugin.LiveCallStart
import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceProviders
import kinuvo.server.rpc.serverRoutes
import kinuvo.testing.harness.scriptedModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kinuvo.protocol.voice.LiveUnavailable
import kinuvo.protocol.voice.LiveVoiceSettings
import kinuvo.server.plugin.LiveCallUnavailable
import kotlin.test.*

class LiveCallRpcTest {
    private class FakeCalls(var failure: LiveCallException? = null, var needs: LiveCallUnavailable? = null) : LiveCallProvider {
        val call = FakeCall()
        val contexts = mutableListOf<VoiceContext>()
        val offers = mutableListOf<String>()
        override suspend fun available() = needs == null
        override suspend fun unavailable() = needs
        override suspend fun start(context: VoiceContext, offerSdp: String): LiveCallStart {
            failure?.let { throw it }
            contexts += context
            offers += offerSdp
            return LiveCallStart("the-answer", call)
        }
    }

    private class FakeCall : LiveCall {
        val events = Channel<LiveCallEvent>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        override suspend fun run(onEvent: suspend (LiveCallEvent) -> Unit) { sent.send("run"); for (event in events) onEvent(event) }
        override suspend fun reply(id: String, text: String) { sent.send("reply $id: $text") }
        override suspend fun notify(text: String) { sent.send("notify: $text") }
        override suspend fun pause() { sent.send("pause") }
        override suspend fun resume() { sent.send("resume") }
        override suspend fun end() { sent.send("end"); events.close() }
    }

    private class Device {
        private val requests = Channel<Frame>(Channel.UNLIMITED)
        private val responses = Channel<Frame.Response>(Channel.UNLIMITED)
        val updates = Channel<LiveCallUpdate>(Channel.UNLIMITED)
        private var id = 0
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                when {
                    frame is Frame.Response -> responses.send(frame)
                    frame is Frame.Event && frame.event == Api.LiveCallUpdate.name -> updates.send(ProtocolJson.decodeFromJsonElement(Api.LiveCallUpdate.payload, frame.payload))
                }
            }
            override suspend fun close() { requests.close(); responses.close(); updates.close() }
        }
        suspend fun <P, R> response(method: Method<P, R>, params: P): Frame.Response {
            val requestId = (++id).toString()
            requests.send(Frame.Request(requestId, method.name, ProtocolJson.encodeToJsonElement(method.params, params)))
            return responses.receive().also { assertEquals(requestId, it.id) }
        }
        suspend fun <P, R> call(method: Method<P, R>, params: P): R {
            val response = response(method, params)
            assertTrue(response.ok, response.toString())
            return ProtocolJson.decodeFromJsonElement(method.result, response.payload)
        }
    }

    private fun peers() = object : PeerRegistry {
        override fun authenticate(device: String, token: String?, pairingCode: String?) = device
        override fun management(token: String) = false
        override fun toolDevices() = emptyList<ToolDevice>()
        override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
    }

    private suspend fun <T> withCall(calls: FakeCalls, enabled: Boolean = true, reply: String = "Known reply", block: suspend CoroutineScope.(Device, RuntimeFixture, SessionInfo) -> T): T = coroutineScope {
        val fixture = RuntimeFixture(voice = VoiceProviders(liveCalls = calls))
        fixture.settings.liveVoiceEnabled = enabled
        val harness = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta(reply), StreamFrame.End()) })
        val session = harness.newSession(fixture.agent)
        val server = ServerRuntime(harness, peers(), serverRoutes())
        val device = Device()
        server.start()
        val serving = launch { server.serve(device.connection) }
        try {
            device.call(Api.Connect, ConnectParams("test"))
            block(device, fixture, session)
        } finally { server.close(); serving.cancelAndJoin() }
    }

    @Test fun aCallStartsFromTheDevicesOfferAndRunsItsDelegationsThroughTheSessionsAgent() = runTest {
        val calls = FakeCalls()
        withCall(calls) { device, fixture, session ->
            assertTrue(device.call(Api.VoiceSetup, Unit).liveCalls)
            val answer = device.call(Api.LiveCallStart, LiveCallParams(fixture.agent.id, session.id, "device-offer"))
            assertEquals("the-answer", answer.answerSdp)
            assertEquals(listOf("device-offer"), calls.offers)
            assertTrue(calls.contexts.single().instructions.contains("Test"))

            device.call(Api.LiveCallPeer, LivePeerParams(answer.callId, LivePeerState.Connecting))
            assertEquals("run", calls.call.sent.receive())
            calls.call.events.send(LiveCallEvent.Ready)
            assertEquals(LiveCallUpdate.Phase(answer.callId, LiveCallPhase.Listening), device.updates.receive())
            calls.call.events.send(LiveCallEvent.Transcript("t1", "what is up", user = true))
            assertEquals(LiveCallUpdate.Transcript(answer.callId, "t1", "what is up", true), device.updates.receive())

            calls.call.events.send(LiveCallEvent.Delegation("d1", "some task"))
            assertEquals(LiveCallUpdate.Phase(answer.callId, LiveCallPhase.Thinking), device.updates.receive())
            assertEquals("reply d1: Known reply", calls.call.sent.receive())

            calls.call.events.send(LiveCallEvent.Transcript("t2", "Known reply", user = false))
            device.updates.receive()
            device.call(Api.LiveCallPause, LiveCallRef(answer.callId))
            assertEquals("pause", calls.call.sent.receive())
            assertEquals(LiveCallUpdate.Phase(answer.callId, LiveCallPhase.Paused), device.updates.receive())
            device.call(Api.LiveCallResume, LiveCallRef(answer.callId))
            assertEquals("resume", calls.call.sent.receive())
            assertEquals(LiveCallUpdate.Phase(answer.callId, LiveCallPhase.Listening), device.updates.receive())

            device.call(Api.LiveCallEnd, LiveCallRef(answer.callId))
            assertEquals("end", calls.call.sent.receive())
            assertEquals(LiveCallUpdate.Ended(answer.callId), device.updates.receive())

            val entries = fixture.transcripts.entries(fixture.agent.id, session.id)
            assertTrue(entries.filterIsInstance<SessionEntry.UserMessage>().single().voice)
            assertEquals(listOf(VoiceTurn(true, "what is up"), VoiceTurn(false, "Known reply")), entries.filterIsInstance<SessionEntry.VoiceCall>().single().turns)
        }
    }

    @Test fun aCallDelegatesToAnExternalAgentThroughItsExecutionDispatch() = runTest {
        val registry = ExecutionRegistry().also {
            it.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
            it.register(AgentRegistration(AgentId("external"), "Agent", "c", cwd = "/tmp"))
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
                override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
            }
        }
        val calls = FakeCalls()
        val fixture = RuntimeFixture(voice = VoiceProviders(liveCalls = calls))
        fixture.settings.liveVoiceEnabled = true
        val server = ServerRuntime(fixture.runtime(), peers(), serverRoutes(), executionRegistry = registry, executionFactory = factory)
        val device = Device()
        server.start()
        val serving = launch { server.serve(device.connection) }
        try {
            device.call(Api.Connect, ConnectParams("test"))
            val session = device.call(Api.CreateSession, AgentRef(AgentId("external")))
            val answer = device.call(Api.LiveCallStart, LiveCallParams(AgentId("external"), session.id, "offer"))
            device.call(Api.LiveCallPeer, LivePeerParams(answer.callId, LivePeerState.Connecting))
            assertEquals("run", calls.call.sent.receive())
            calls.call.events.send(LiveCallEvent.Delegation("d1", "task"))
            assertEquals(LiveCallUpdate.Phase(answer.callId, LiveCallPhase.Thinking), device.updates.receive())
            assertEquals("reply d1: external reply", calls.call.sent.receive())
            calls.call.events.send(LiveCallEvent.Transcript("t1", "external reply", user = false))
            device.updates.receive()
            device.call(Api.LiveCallEnd, LiveCallRef(answer.callId))
            assertEquals("end", calls.call.sent.receive())
            assertEquals(LiveCallUpdate.Ended(answer.callId), device.updates.receive())
            val entries = fixture.transcripts.entries(AgentId("external"), session.id)
            assertTrue(entries.filterIsInstance<SessionEntry.UserMessage>().single().voice)
            assertEquals("external reply", entries.filterIsInstance<SessionEntry.VoiceCall>().single().turns.single().text)
        } finally { server.close(); serving.cancelAndJoin() }
    }

    @Test fun anOlderDevicesServerHeldLiveVoiceMethodsAreUnknownAndFailCleanly() = runTest {
        withCall(FakeCalls()) { device, _, _ ->
            val start = device.response(Method("voice.live.start", Unit.serializer(), Unit.serializer()), Unit)
            assertFalse(start.ok)
            assertTrue(device.call(Api.VoiceSetup, Unit).liveCalls)
        }
    }

    @Test fun aDeviceLearnsWhyLiveVoiceIsUnavailableAndWhichSignInFixesIt() = runTest {
        val calls = FakeCalls(needs = LiveCallUnavailable("Needs a device code.", "codex"))
        withCall(calls) { device, _, _ ->
            assertEquals(LiveVoiceSettings(available = false, enabled = true, LiveUnavailable("Needs a device code.", "codex")), device.call(Api.LiveVoiceSettings, Unit))
            assertFalse(device.call(Api.VoiceSetup, Unit).liveCalls)
            calls.needs = null
            assertEquals(LiveVoiceSettings(available = true, enabled = true), device.call(Api.LiveVoiceSettings, Unit))
            assertTrue(device.call(Api.VoiceSetup, Unit).liveCalls)
        }
    }

    @Test fun theProviderFailingToStartIsAnErrorWithItsMessageAndLeavesNoCall() = runTest {
        val calls = FakeCalls(LiveCallException("ChatGPT's usage limit is reached."))
        withCall(calls) { device, fixture, session ->
            val params = LiveCallParams(fixture.agent.id, session.id, "offer")
            val failed = device.response(Api.LiveCallStart, params)
            assertFalse(failed.ok)
            assertTrue(failed.toString().contains("ChatGPT's usage limit is reached."))
            calls.failure = null
            assertEquals("the-answer", device.call(Api.LiveCallStart, params).answerSdp)
        }
    }

    @Test fun callsAreRefusedWhenLiveVoiceIsOffOrABlankOfferComes() = runTest {
        withCall(FakeCalls(), enabled = false) { device, fixture, session ->
            assertFalse(device.call(Api.VoiceSetup, Unit).liveCalls)
            assertFalse(device.response(Api.LiveCallStart, LiveCallParams(fixture.agent.id, session.id, "offer")).ok)
        }
        withCall(FakeCalls()) { device, fixture, session ->
            assertFalse(device.response(Api.LiveCallStart, LiveCallParams(fixture.agent.id, session.id, " ")).ok)
        }
    }

    @Test fun oneCallPerConnectionAndTheDevicesFailedPeerEndsItWithAnError() = runTest {
        val calls = FakeCalls()
        withCall(calls) { device, fixture, session ->
            val params = LiveCallParams(fixture.agent.id, session.id, "offer")
            val answer = device.call(Api.LiveCallStart, params)
            assertFalse(device.response(Api.LiveCallStart, params).ok)
            device.call(Api.LiveCallPeer, LivePeerParams(answer.callId, LivePeerState.Connecting))
            assertEquals("run", calls.call.sent.receive())
            device.call(Api.LiveCallPeer, LivePeerParams(answer.callId, LivePeerState.Failed))
            assertEquals("end", calls.call.sent.receive())
            assertEquals(LiveCallUpdate.Ended(answer.callId, "The call connection failed."), device.updates.receive())
            assertEquals("the-answer", device.call(Api.LiveCallStart, params).answerSdp)
        }
    }

    @Test fun controlsForOtherCallIdsAreIgnored() = runTest {
        val calls = FakeCalls()
        withCall(calls) { device, fixture, session ->
            val answer = device.call(Api.LiveCallStart, LiveCallParams(fixture.agent.id, session.id, "offer"))
            device.call(Api.LiveCallPeer, LivePeerParams(answer.callId, LivePeerState.Connected))
            assertEquals("run", calls.call.sent.receive())
            device.call(Api.LiveCallPause, LiveCallRef("unknown"))
            device.call(Api.LiveCallEnd, LiveCallRef("unknown"))
            assertTrue(calls.call.sent.tryReceive().isFailure)
        }
    }
}
