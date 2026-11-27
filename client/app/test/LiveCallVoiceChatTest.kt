package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallVoiceChatTest {
    private class FakePeer(val log: MutableList<String>) : LiveCallPeer {
        override val state = MutableStateFlow(LivePeerState.Connecting)
        override val levels = MutableStateFlow(LiveCallLevels())
        var offerFailure: String? = null
        var accepted: String? = null
        var closed = false
        override suspend fun createOffer(): String { offerFailure?.let { throw LiveCallException(it) }; return "offer" }
        override suspend fun accept(answerSdp: String) { accepted = answerSdp }
        override fun setMuted(muted: Boolean) { log += "peer.mute($muted)" }
        override fun setSpeakerMuted(muted: Boolean) { log += "peer.speaker(${!muted})" }
        override fun close() { closed = true; log += "peer.close" }
    }

    private class Audio(val peer: FakePeer?, val supported: Boolean = true) : LiveAudio {
        override val callPeerSupported get() = supported
        override fun createCallPeer() = peer
        override fun authorizeAudio(completion: (String?) -> Unit) = completion(null)
        val cues = mutableListOf<ByteArray>()
        override fun playCue(pcm: ByteArray, sampleRate: Int) { cues += pcm }
    }

    private class Fixture(private val scope: TestScope) {
        val log = mutableListOf<String>()
        val peer = FakePeer(log)
        val audio = Audio(peer)
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val offers = mutableListOf<LiveCallParams>()
        val peerStates = mutableListOf<LivePeerState>()
        var answerFailure: String? = null
        val connection = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val r = frame as? Frame.Request ?: return
                log += "call ${r.method}"
                var failure: String? = null
                val payload = when (r.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.LiveCallStart.name -> {
                        offers += ProtocolJson.decodeFromJsonElement(Api.LiveCallStart.params, r.params)
                        failure = answerFailure
                        ProtocolJson.encodeToJsonElement(LiveCallAnswer("call", "answer"))
                    }
                    Api.LiveCallPeer.name -> { peerStates += ProtocolJson.decodeFromJsonElement(Api.LiveCallPeer.params, r.params).state; buildJsonObject {} }
                    else -> buildJsonObject {}
                }
                frames.send(if (failure != null) Frame.Response(r.id, false, error = WireError(WireErrorCode.Internal, failure)) else Frame.Response(r.id, true, payload))
            }
            override suspend fun close() {}
        }
        val client = KinuvoClient("local:test", "test", connection = { connection }, dispatcher = StandardTestDispatcher(scope.testScheduler))
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }))
        val chat = LiveCallVoiceChat(server, audio) { AgentId("agent") to SessionId("session") }

        fun update(update: LiveCallUpdate) { frames.trySend(Frame.Event(Api.LiveCallUpdate.name, ProtocolJson.encodeToJsonElement(Api.LiveCallUpdate.payload, update), 1)) }
        suspend fun open() { client.connect(); server.open(client, "local:test") }
        fun close() { chat.end(); scope.testScheduler.runCurrent(); server.close(); client.close(); Dispatchers.resetMain() }
    }

    private fun TestScope.fixture(): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return Fixture(this)
    }

    @Test fun holdsTheCallAndMapsServerUpdates() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            runCurrent()
            assertEquals(listOf(LiveCallParams(AgentId("agent"), SessionId("session"), "offer")), f.offers)
            assertEquals("answer", f.peer.accepted)
            assertEquals(VoicePhase.Starting, f.chat.state.value.phase)
            assertTrue(f.chat.state.value.live)
            assertEquals(listOf(LivePeerState.Connecting), f.peerStates)

            f.peer.state.value = LivePeerState.Connected
            f.update(LiveCallUpdate.Phase("other", LiveCallPhase.Speaking))
            runCurrent()
            assertEquals(listOf(LivePeerState.Connecting, LivePeerState.Connected), f.peerStates)
            assertEquals(VoicePhase.Starting, f.chat.state.value.phase)

            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            assertEquals(VoicePhase.Listening, f.chat.state.value.phase)
            assertEquals(1, f.audio.cues.count { it.contentEquals(VoiceCue.Ready.pcm(24_000)) })

            f.update(LiveCallUpdate.Transcript("call", "u1", "What's the weather", user = true, final = false))
            f.update(LiveCallUpdate.Transcript("call", "a1", "Checking", user = false))
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Thinking))
            runCurrent()
            assertEquals("What's the weather", f.chat.state.value.heard)
            assertEquals("Checking", f.chat.state.value.caption)
            assertEquals(VoicePhase.Thinking, f.chat.state.value.phase)

            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Speaking))
            f.peer.levels.value = LiveCallLevels(input = 0.2f, output = 0.6f)
            runCurrent()
            assertEquals(VoicePhase.Speaking, f.chat.state.value.phase)
            assertEquals(0.2f, f.chat.state.value.inputLevel)
            assertEquals(0.6f, f.chat.state.value.outputLevel)

            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            assertEquals(VoicePhase.Listening, f.chat.state.value.phase)
            assertFalse(f.peer.closed)
        } finally { f.close() }
    }

    @Test fun pauseMutesTheMicrophoneAtOnceThenTellsTheServer() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            runCurrent()
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            f.log.clear()
            f.chat.pause()
            assertEquals(listOf("peer.mute(true)"), f.log)
            assertTrue(f.chat.state.value.paused)
            runCurrent()
            assertEquals(listOf("peer.mute(true)", "call ${Api.LiveCallPause.name}"), f.log)
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Paused))
            runCurrent()
            assertTrue(f.chat.state.value.paused)
            assertEquals(VoicePhase.Listening, f.chat.state.value.phase)

            f.log.clear()
            f.chat.resume()
            assertEquals(listOf("peer.mute(false)"), f.log)
            runCurrent()
            assertEquals("call ${Api.LiveCallResume.name}", f.log.last())
            assertFalse(f.chat.state.value.paused)
        } finally { f.close() }
    }

    @Test fun pausedBeforeAttachStartsMutedAndTellsTheServerOnceAttached() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            f.chat.pause()
            runCurrent()
            assertTrue(f.chat.state.value.paused)
            assertTrue("peer.mute(true)" in f.log)
            assertTrue(f.log.none { it == "call ${Api.LiveCallPause.name}" })
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            assertTrue(f.log.any { it == "call ${Api.LiveCallPause.name}" })
        } finally { f.close() }
    }

    @Test fun endTellsTheServerThenClosesThePeer() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            runCurrent()
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            f.log.clear()
            f.chat.end()
            assertEquals(VoicePhase.Off, f.chat.state.value.phase)
            assertNull(f.chat.state.value.error)
            assertFalse(f.chat.state.value.live)
            runCurrent()
            assertEquals(listOf("peer.mute(true)", "call ${Api.LiveCallEnd.name}", "peer.close"), f.log)
            assertTrue(f.audio.cues.any { it.contentEquals(VoiceCue.Ended.pcm(24_000)) })
        } finally { f.close() }
    }

    @Test fun aFailedPeerEndsTheCallWithAnError() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            runCurrent()
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            f.peer.state.value = LivePeerState.Failed
            runCurrent()
            assertEquals(LivePeerState.Failed, f.peerStates.last())
            assertEquals(VoicePhase.Off, f.chat.state.value.phase)
            assertTrue(f.chat.state.value.live)
            assertNotNull(f.chat.state.value.error)
            assertTrue(f.log.contains("call ${Api.LiveCallEnd.name}"))
            assertTrue(f.peer.closed)
        } finally { f.close() }
    }

    @Test fun serverEndedWithAnErrorKeepsItWithoutEndingAgain() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            runCurrent()
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            f.update(LiveCallUpdate.Ended("call", "The model went away"))
            runCurrent()
            assertEquals("The model went away", f.chat.state.value.error)
            assertTrue(f.chat.state.value.live)
            assertTrue(f.peer.closed)
            assertTrue(f.log.none { it == "call ${Api.LiveCallEnd.name}" })
        } finally { f.close() }
    }

    @Test fun serverEndedCleanlyLeavesTheCall() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.start()
            runCurrent()
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            f.update(LiveCallUpdate.Ended("call"))
            runCurrent()
            assertEquals(VoicePhase.Off, f.chat.state.value.phase)
            assertNull(f.chat.state.value.error)
            assertTrue(f.peer.closed)
            assertTrue(f.log.none { it == "call ${Api.LiveCallEnd.name}" })
        } finally { f.close() }
    }

    @Test fun peerAndServerStartFailuresAreShown() = runTest {
        val f = fixture()
        try {
            f.open()
            f.peer.offerFailure = "No microphone found."
            f.chat.start()
            runCurrent()
            assertEquals("No microphone found.", f.chat.state.value.error)
            assertTrue(f.peer.closed)
            assertTrue(f.offers.isEmpty())
            f.chat.end()

            f.peer.offerFailure = null
            f.peer.closed = false
            f.answerFailure = "Sign in to ChatGPT first"
            f.chat.start()
            runCurrent()
            assertEquals("Sign in to ChatGPT first", f.chat.state.value.error)
            assertTrue(f.peer.closed)
            assertNull(f.peer.accepted)
        } finally { f.close() }
    }

    @Test fun speakerAndCuesFollowTheSpeakerSetting() = runTest {
        val f = fixture()
        try {
            f.open()
            f.chat.speaker(false)
            f.chat.start()
            runCurrent()
            assertTrue("peer.speaker(false)" in f.log)
            f.update(LiveCallUpdate.Phase("call", LiveCallPhase.Listening))
            runCurrent()
            assertTrue(f.audio.cues.none { it.contentEquals(VoiceCue.Ready.pcm(24_000)) })
            f.chat.speaker(true)
            assertEquals("peer.speaker(true)", f.log.last())
        } finally { f.close() }
    }

    @Test fun aDeviceWithoutACallPeerCannotHoldTheCall() = runTest {
        val f = fixture()
        try {
            f.open()
            val chat = LiveCallVoiceChat(f.server, Audio(null)) { AgentId("agent") to SessionId("session") }
            chat.start()
            runCurrent()
            assertNotNull(chat.state.value.error)
        } finally { f.close() }
    }

    private class RecordingChat : VoiceChat {
        var starts = 0
        override val state = MutableStateFlow(VoiceChatState())
        override fun start() { starts++ }
        override fun end() {}
        override fun pause() {}
        override fun resume() {}
        override fun hold() {}
        override fun letGo() {}
        override fun speaker(on: Boolean) {}
        override fun background() {}
        override fun foreground() {}
    }

    @Test fun deviceHeldCallsAreUsedOnlyWhenTheServerBrokersThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            suspend fun pick(setup: VoiceSetup, held: Boolean = true): Pair<Int, Int> {
                val speech = RecordingChat(); val brokered = RecordingChat()
                val chat = PickedVoiceChat(speech, brokered.takeIf { held }, backgroundScope) { setup }
                chat.start()
                runCurrent()
                return speech.starts to brokered.starts
            }
            assertEquals(0 to 1, pick(VoiceSetup(liveCalls = true)))
            assertEquals(1 to 0, pick(VoiceSetup(liveCalls = true), held = false))
            assertEquals(1 to 0, pick(VoiceSetup()))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun aServerOnlyOfferingTheRemovedServerHeldPathUsesDeviceSpeech() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val speech = RecordingChat(); val brokered = RecordingChat()
            val setup = ProtocolJson.decodeFromString(VoiceSetup.serializer(), """{"transcriber":true,"speechToSpeech":true}""")
            PickedVoiceChat(speech, brokered, backgroundScope) { setup }.start()
            runCurrent()
            assertEquals(1 to 0, speech.starts to brokered.starts)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun startingWhileTheServerIsAskedWhichCallToUseDoesNotStartDeviceSpeech() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val speech = RecordingChat(); val brokered = RecordingChat()
            val selection = CompletableDeferred<Unit>()
            val chat = PickedVoiceChat(speech, brokered, backgroundScope) { selection.await(); VoiceSetup(liveCalls = true) }
            chat.start()
            runCurrent()
            chat.start()
            assertEquals(0, speech.starts)
            selection.complete(Unit)
            runCurrent()
            assertEquals(0 to 1, speech.starts to brokered.starts)
        } finally { Dispatchers.resetMain() }
    }
}
