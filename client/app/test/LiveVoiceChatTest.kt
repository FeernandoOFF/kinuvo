package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.navigation.Route
import kinuvo.client.KinuvoClient
import kinuvo.client.ConnectionState
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.usage.*
import kinuvo.protocol.voice.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceChatTest {
    private class Audio : LiveAudio {
        override var echoes = false
        var endRequested: () -> Unit = {}
        override fun onEndRequested(end: () -> Unit) { endRequested = end }
        var muteChanged: (Boolean) -> Unit = {}
        val muted = mutableListOf<Boolean>()
        override fun onInputMuteChanged(changed: (Boolean) -> Unit) { muteChanged = changed }
        override fun setInputMuted(muted: Boolean) { this.muted += muted; muteChanged(muted) }
        var microphone: (ByteArray) -> Unit = {}
        val played = mutableListOf<ByteArray>()
        val cues = mutableListOf<ByteArray>()
        var cueActive = false
        override fun playCue(pcm: ByteArray, sampleRate: Int) { cues += pcm; cueActive = true }
        override fun stopCue() { cueActive = false }
        var flushes = 0
        var stops = 0
        var rate = 0
        var waiting = 0
        var failure: String? = null
        var fail: (String) -> Unit = {}
        var authorize: (((String?) -> Unit) -> Unit) = { it(null) }
        override fun authorizeAudio(completion: (String?) -> Unit) = authorize(completion)
        override fun startLive(sampleRate: Int, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) { rate = sampleRate; microphone = onAudio; fail = onError; failure?.let(onError) }
        override fun playLive(pcm: ByteArray) { played += pcm }
        override fun queuedMillis() = waiting
        override fun flushLive() { flushes++ }
        override fun stopLive() { stops++; microphone = {} }
    }

    @Test fun streamsTheMicrophoneAndPlaysTheReply() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val inputs = mutableListOf<LiveVoiceInput>()
        val calls = mutableListOf<String>()
        var autoReady = false
        fun output(o: LiveVoiceOutput) = frames.trySend(Frame.Event(Api.LiveVoiceOutput.name, ProtocolJson.encodeToJsonElement(Api.LiveVoiceOutput.payload, o), 1))
        val connection = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                if (frame is Frame.Event) { inputs += ProtocolJson.decodeFromJsonElement(Api.LiveVoiceInput.payload, frame.payload); return }
                val r = frame as Frame.Request
                calls += r.method
                val payload = when (r.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.LiveVoiceStart.name -> ProtocolJson.encodeToJsonElement(LiveVoiceCall("call", 24_000)).also { if (autoReady) output(LiveVoiceOutput.Ready("call")) }
                    else -> buildJsonObject {}
                }
                frames.send(Frame.Response(r.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { connection }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        try {
            client.connect(); server.open(client, "local:test")
            val audio = Audio()
            val chat = LiveVoiceChat(server, audio, testScheduler.timeSource) { AgentId("agent") to SessionId("session") }
            chat.start()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(VoicePhase.Starting, chat.state.value.phase)
            assertEquals(2, audio.cues.size)
            assertTrue(audio.cueActive)
            audio.echoes = true
            audio.waiting = 250
            output(LiveVoiceOutput.Ready("call"))
            runCurrent()
            audio.microphone(byteArrayOf(41, 42))
            runCurrent()
            assertTrue(inputs.none { it is LiveVoiceInput.Audio })
            audio.waiting = 0
            assertEquals(VoicePhase.Listening, chat.state.value.phase)
            assertFalse(audio.cueActive)
            assertContentEquals(VoiceCue.Ready.pcm(24_000), audio.played.single())
            output(LiveVoiceOutput.Ready("call"))
            output(LiveVoiceOutput.Listening("call"))
            advanceTimeBy(300); runCurrent()
            assertEquals(1, audio.played.size)
            assertTrue(inputs.none { it is LiveVoiceInput.Played })
            assertEquals(0f, chat.state.value.outputLevel)
            assertEquals(VoicePhase.Listening, chat.state.value.phase)
            audio.played.clear()
            autoReady = true
            assertEquals(24_000, audio.rate)

            audio.microphone(byteArrayOf(1, 2))
            output(LiveVoiceOutput.Transcript("call", "u1", "What's", user = true, final = false))
            runCurrent()
            assertEquals("What's", chat.state.value.heard)
            output(LiveVoiceOutput.Transcript("call", "u1", "What's up?", user = true))
            output(LiveVoiceOutput.Audio("call", "a1", Base64.encode(byteArrayOf(3, 4))))
            output(LiveVoiceOutput.Audio("other", "a2", Base64.encode(byteArrayOf(9))))
            advanceTimeBy(100); runCurrent()
            assertEquals(LiveVoiceInput.Audio("call", Base64.encode(byteArrayOf(1, 2))), inputs.first())
            assertEquals("What's up?", chat.state.value.heard)
            assertEquals(VoicePhase.Speaking, chat.state.value.phase)
            assertContentEquals(byteArrayOf(3, 4), audio.played.single(), "Another call's audio must not play")
            output(LiveVoiceOutput.Transcript("call", "a1", "Not", user = false, final = false))
            runCurrent()
            assertEquals("Not", chat.state.value.caption)
            output(LiveVoiceOutput.Transcript("call", "a1", "Not much", user = false, final = false))
            runCurrent()
            assertEquals("Not much", chat.state.value.caption)
            output(LiveVoiceOutput.Transcript("call", "a1", "Not much.", user = false))
            runCurrent()
            assertEquals("Not much.", chat.state.value.caption)
            output(LiveVoiceOutput.Transcript("call", "a2", "And", user = false, final = false))
            runCurrent()
            assertEquals("And", chat.state.value.caption, "A new reply replaces the last caption")
            assertEquals("What's up?", chat.state.value.heard)

            output(LiveVoiceOutput.Audio("call", "a1", Base64.encode(ByteArray(1_920) { 7 })))
            runCurrent()
            assertEquals(1, audio.played.size, "An empty playback queue prebuffers")
            advanceTimeBy(79); runCurrent()
            assertEquals(1, audio.played.size)
            output(LiveVoiceOutput.Audio("call", "a1", Base64.encode(ByteArray(1_920) { 8 })))
            runCurrent()
            assertEquals(3_840, audio.played.last().size)

            output(LiveVoiceOutput.Audio("call", "a1", Base64.encode(byteArrayOf(5, 6))))
            runCurrent()
            val beforePause = audio.played.size
            chat.pause()
            chat.pause()
            runCurrent()
            assertTrue(chat.state.value.paused)
            assertEquals(listOf(false, true), audio.muted)
            assertEquals(1, inputs.count { it is LiveVoiceInput.Pause })
            assertEquals(0, audio.flushes, "Muting keeps the reply playing")
            assertTrue(inputs.none { it is LiveVoiceInput.Interrupt })
            assertTrue(inputs.any { it is LiveVoiceInput.Pause })
            assertEquals(VoicePhase.Speaking, chat.state.value.phase)
            audio.microphone(byteArrayOf(5))
            runCurrent()
            assertTrue(inputs.none { it is LiveVoiceInput.Audio && it.base64 == Base64.encode(byteArrayOf(5)) }, "Muted audio must stay on the Device")
            output(LiveVoiceOutput.Audio("call", "a1", Base64.encode(byteArrayOf(9, 10))))
            advanceTimeBy(150); runCurrent()
            assertTrue(audio.played.size > beforePause, "Replies play while muted")
            assertContentEquals(byteArrayOf(5, 6, 9, 10), audio.played.last())

            assertContentEquals(VoiceCue.Muted.pcm(24_000), audio.cues.last())
            val systemCues = audio.cues.size
            audio.muteChanged(false)
            runCurrent()
            assertFalse(chat.state.value.paused)
            assertEquals(listOf(false, true), audio.muted, "A system unmute is already in effect")
            assertEquals(1, inputs.count { it is LiveVoiceInput.Resume })
            audio.muteChanged(true)
            runCurrent()
            assertTrue(chat.state.value.paused)
            assertEquals(systemCues, audio.cues.size, "The system plays its own mute cues")
            chat.resume()
            runCurrent()
            assertTrue(inputs.any { it is LiveVoiceInput.Resume })
            assertContentEquals(VoiceCue.Unmuted.pcm(24_000), audio.cues.last())
            audio.microphone(byteArrayOf(13))
            runCurrent()
            assertTrue(inputs.none { it is LiveVoiceInput.Audio && it.base64 == Base64.encode(byteArrayOf(13)) }, "The model must not hear the cue")
            advanceTimeBy(VoiceCue.Unmuted.millis() + 100L)
            audio.microphone(byteArrayOf(6))
            runCurrent()
            assertTrue(inputs.any { it is LiveVoiceInput.Audio && it.base64 == Base64.encode(byteArrayOf(6)) })

            chat.interrupt()
            runCurrent()
            assertTrue(audio.flushes > 0)
            assertTrue(inputs.any { it is LiveVoiceInput.Interrupt && it.item == "a1" })
            assertContentEquals(VoiceCue.Interrupt.pcm(24_000), audio.cues.last())
            assertEquals(VoicePhase.Listening, chat.state.value.phase)
            assertFalse(chat.state.value.paused)
            val beforeHold = audio.played.size
            output(LiveVoiceOutput.Audio("call", "a1", Base64.encode(ByteArray(4_000))))
            advanceTimeBy(150); runCurrent()
            assertEquals(beforeHold, audio.played.size, "Interrupted audio must stay discarded")
            output(LiveVoiceOutput.Audio("call", "a3", Base64.encode(byteArrayOf(7, 8))))
            runCurrent()
            assertEquals(beforeHold, audio.played.size)
            audio.waiting = 500
            output(LiveVoiceOutput.Done("call", "a3"))
            runCurrent()
            assertContentEquals(byteArrayOf(7, 8), audio.played.last())
            advanceTimeBy(300); runCurrent()
            assertEquals(VoicePhase.Speaking, chat.state.value.phase)
            audio.waiting = 0
            advanceTimeBy(300); runCurrent()
            assertEquals(VoicePhase.Listening, chat.state.value.phase)

            chat.pause(); runCurrent()
            val pauses = inputs.count { it is LiveVoiceInput.Pause }
            chat.hold(); runCurrent()
            assertTrue(chat.state.value.holding)
            assertFalse(chat.state.value.paused, "Holding talks while muted")
            assertFalse(audio.muted.last(), "Holding unmutes the system microphone")
            assertTrue(inputs.last() is LiveVoiceInput.Resume)
            chat.letGo(); runCurrent()
            assertTrue(chat.state.value.paused, "Letting go mutes again")
            assertTrue(audio.muted.last())
            assertEquals(pauses + 1, inputs.count { it is LiveVoiceInput.Pause })
            chat.resume(); advanceTimeBy(500); runCurrent()

            val beforeThinking = audio.played.size
            output(LiveVoiceOutput.Thinking("call"))
            advanceTimeBy(800); runCurrent()
            assertContentEquals(VoiceCue.Thinking.pcm(24_000), audio.played.last())
            advanceTimeBy(1_600); runCurrent()
            assertEquals(beforeThinking + 2, audio.played.size, "The thinking cue repeats")
            val flushes = audio.flushes
            output(LiveVoiceOutput.Audio("call", "a4", Base64.encode(byteArrayOf(1, 1))))
            advanceTimeBy(150); runCurrent()
            assertEquals(flushes + 1, audio.flushes, "A reply cuts the thinking cue")
            assertContentEquals(byteArrayOf(1, 1), audio.played.last())
            advanceTimeBy(3_000); runCurrent()
            assertContentEquals(byteArrayOf(1, 1), audio.played.last(), "No thinking cue while speaking")
            output(LiveVoiceOutput.Done("call", "a4"))
            advanceTimeBy(300); runCurrent()

            chat.background()
            runCurrent()
            assertFalse(chat.state.value.paused, "A live call stays live in the background")
            audio.microphone(byteArrayOf(11, 12))
            runCurrent()
            assertTrue(inputs.any { it is LiveVoiceInput.Audio && it.base64 == Base64.encode(byteArrayOf(11, 12)) })

            val staleMuteChanged = audio.muteChanged
            output(LiveVoiceOutput.Ended("call", "Usage limit reached"))
            advanceTimeBy(1_000); runCurrent()
            assertEquals("Usage limit reached", chat.state.value.error)
            assertTrue(chat.state.value.live, "A failed call stays a live call")
            assertEquals(VoicePhase.Off, chat.state.value.phase)
            chat.start()
            advanceTimeBy(1_000); runCurrent()
            staleMuteChanged(true)
            runCurrent()
            assertFalse(chat.state.value.paused, "An ended call must not mute the next call")
            audio.endRequested()
            runCurrent()
            assertEquals(VoicePhase.Off, chat.state.value.phase)
            assertTrue(Api.LiveVoiceEnd.name in calls)

            repeat(3) {
                chat.start()
                runCurrent()
                assertEquals(VoicePhase.Listening, chat.state.value.phase)
                val stops = audio.stops
                chat.end()
                assertContentEquals(VoiceCue.Ended.pcm(24_000), audio.cues.last())
                chat.start()
                runCurrent()
                assertEquals(stops + 1, audio.stops, "Cancelled calls must not stop the next call's audio")
                assertEquals(VoicePhase.Listening, chat.state.value.phase)
                audio.microphone(byteArrayOf(21, 22))
                runCurrent()
                assertTrue(inputs.any { it is LiveVoiceInput.Audio && it.base64 == Base64.encode(byteArrayOf(21, 22)) })
                output(LiveVoiceOutput.Ended("call", null))
                runCurrent()
                assertEquals(VoicePhase.Off, chat.state.value.phase)
                val endedStops = audio.stops
                chat.end()
                assertEquals(endedStops, audio.stops, "An already ended chat must not stop shared audio again")
            }
            chat.start()
            runCurrent()
            audio.endRequested()
            chat.start()
            runCurrent()
            assertEquals(VoicePhase.Listening, chat.state.value.phase)
            audio.fail("The microphone could not start")
            runCurrent()
            assertEquals("The microphone could not start", chat.state.value.error)
            assertEquals(VoicePhase.Off, chat.state.value.phase)
            assertFalse(audio.cueActive)
            chat.end()
            assertFalse(chat.state.value.live)
            chat.speaker(false)
            val cueCount = audio.cues.size
            val liveCount = audio.played.size
            chat.start(); runCurrent(); chat.end()
            assertEquals(cueCount, audio.cues.size)
            assertEquals(liveCount, audio.played.size)
            chat.speaker(true)
            val owner = LiveCalls(backgroundScope)
            repeat(3) {
                owner.start(AgentId("agent"), SessionId("session"), chat)
                runCurrent()
                assertEquals(VoicePhase.Listening, owner.state.value?.voice?.phase)
                output(LiveVoiceOutput.Ended("call", null))
                runCurrent()
                assertNull(owner.state.value)
            }
            owner.start(AgentId("agent"), SessionId("session"), chat)
            runCurrent()
            owner.end()
            owner.start(AgentId("agent"), SessionId("session"), chat)
            runCurrent()
            assertEquals(VoicePhase.Listening, owner.state.value?.voice?.phase)
            owner.end()
        } finally {
            server.close(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun connectingStopsOnFailureOrUserEnd() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        server.open(KinuvoClient("local:test", "test"), "local:test")
        try {
            val audio = Audio()
            var permission: (String?) -> Unit = {}
            audio.authorize = { permission = it }
            val chat = LiveVoiceChat(server, audio) { null }
            chat.start(); runCurrent()
            assertTrue(audio.cueActive)
            permission("Microphone denied"); runCurrent()
            assertFalse(audio.cueActive)
            assertEquals("Microphone denied", chat.state.value.error)
            val failedCues = audio.cues.size
            advanceTimeBy(2_000); runCurrent()
            assertEquals(failedCues, audio.cues.size)
            chat.end()
            assertEquals(failedCues, audio.cues.size)
            chat.start(); runCurrent()
            chat.speaker(false)
            assertFalse(audio.cueActive)
            val mutedCues = audio.cues.size
            advanceTimeBy(2_000); runCurrent()
            assertEquals(mutedCues, audio.cues.size)
            chat.speaker(true)
            advanceTimeBy(1_000); runCurrent()
            assertTrue(audio.cueActive)
            chat.end()
            assertContentEquals(VoiceCue.Ended.pcm(24_000), audio.cues.last())
            val endedCues = audio.cues.size
            advanceTimeBy(2_000); runCurrent()
            assertEquals(endedCues, audio.cues.size)
            assertEquals(VoicePhase.Off, chat.state.value.phase)
        } finally {
            server.close(); Dispatchers.resetMain()
        }
    }

    @Test fun cueLengthsAndEnvelopes() {
        for (rate in listOf(16_000, 24_000, 48_000)) {
            for (cue in VoiceCue.entries) {
                val pcm = cue.pcm(rate)
                assertEquals(cue.notes.sumOf { rate * it.second / 1000 } * 2, pcm.size)
                val samples = pcm.indices.step(2).map { ((pcm[it].toInt() and 255) or (pcm[it + 1].toInt() shl 8)).toShort().toInt() }
                val peak = (cue.gain * Short.MAX_VALUE).toInt()
                assertEquals(0, samples.first())
                assertEquals(0, samples.last())
                assertTrue(samples.all { it in -peak..peak })
                assertTrue(samples.any { it > peak * 3 / 4 })
            }
        }
    }

    @Test fun consecutiveCallsEachStream() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var frames = Channel<Frame>(Channel.UNLIMITED)
        val inputs = mutableListOf<LiveVoiceInput>()
        val ended = mutableListOf<String>()
        var started = 0
        fun output(o: LiveVoiceOutput) = frames.trySend(Frame.Event(Api.LiveVoiceOutput.name, ProtocolJson.encodeToJsonElement(Api.LiveVoiceOutput.payload, o), 1))
        val connection = object : FrameConnection {
            override val incoming get() = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                if (frame is Frame.Event) { inputs += ProtocolJson.decodeFromJsonElement(Api.LiveVoiceInput.payload, frame.payload); return }
                val r = frame as Frame.Request
                val payload = when (r.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.LiveVoiceStart.name -> ProtocolJson.encodeToJsonElement(LiveVoiceCall("call${++started}", 24_000)).also { output(LiveVoiceOutput.Ready("call$started")) }
                    Api.LiveVoiceEnd.name -> buildJsonObject {}.also { ended += ProtocolJson.decodeFromJsonElement(Api.LiveVoiceEnd.params, r.params).callId }
                    else -> buildJsonObject {}
                }
                frames.send(Frame.Response(r.id, true, payload))
            }
            override suspend fun close() {}
        }
        val client = KinuvoClient("local:test", "test", connection = { connection }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        try {
            client.connect(retry = true); server.open(client, "local:test")
            val audio = Audio()
            val calls = LiveCalls(backgroundScope)
            val speech = object : VoiceChat {
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
            val live = calls.attach(AgentId("agent"), SessionId("session"), backgroundScope, { AgentId("agent") to SessionId("session") }) { _, _ -> LiveVoiceChat(server, audio) { AgentId("agent") to SessionId("session") } }
            val selection = CompletableDeferred<Unit>()
            val screen = PickedVoiceChat(speech, live, backgroundScope) { selection.await(); true }
            screen.start()
            runCurrent()
            screen.start()
            assertEquals(0, speech.starts)
            selection.complete(Unit)
            fun call(n: Int, hangUp: () -> Unit) {
                screen.start()
                advanceTimeBy(1_000); runCurrent()
                assertEquals(VoicePhase.Listening, calls.state.value?.voice?.phase, "Call $n")
                screen.start()
                runCurrent()
                assertEquals(0, speech.starts)
                audio.microphone(byteArrayOf(n.toByte(), 0))
                advanceTimeBy(1_000); runCurrent()
                assertEquals(VoicePhase.Listening, calls.state.value?.voice?.phase, "Call $n keeps going")
                assertTrue(inputs.any { it is LiveVoiceInput.Audio && it.callId == "call$n" && it.base64 == Base64.encode(byteArrayOf(n.toByte(), 0)) }, "Call $n streams")
                hangUp()
                advanceTimeBy(1_000); runCurrent()
                assertNull(calls.state.value, "Call $n ends")
                assertEquals(VoicePhase.Off, screen.state.value.phase)
            }
            call(1) { calls.end() }
            call(2) { output(LiveVoiceOutput.Ended("call2")) }
            call(3) { screen.end() }
            call(4) { calls.end() }
            audio.failure = "Couldn't start call audio"
            screen.start()
            advanceTimeBy(1_000); runCurrent()
            assertEquals("Couldn't start call audio", calls.state.value?.voice?.error, "The call screen shows why it ended")
            assertEquals("Couldn't start call audio", screen.state.value.error)
            assertTrue(screen.state.value.live)
            assertTrue(calls.state.value?.voice?.live == true)
            calls.end()
            runCurrent()
            assertNull(calls.state.value)
            assertEquals(VoiceChatState(), screen.state.value)
            audio.failure = null
            call(6) { calls.end() }
            assertEquals(listOf("call1", "call2", "call3", "call4", "call5", "call6"), ended)
            assertEquals(0, speech.starts, "Device speech must not take the audio session")
            val engine = object : SpeechEngine, LiveAudio by audio {
                override fun authorize(completion: (String?) -> Unit) = completion(null)
                override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) { error("Audio is owned by the call") }
                override fun speak(text: String, onDone: () -> Unit) { error("Audio is owned by the call") }
                override fun stopSpeaking() { error("Unrelated speech must not stop playback") }
                override fun stop() { error("Unrelated speech must not release audio") }
            }
            val owned = LiveVoiceChat(server, engine) { AgentId("agent") to SessionId("session") }
            owned.start()
            runCurrent()
            val unrelated = VoiceViewModel(engine, SessionViewModel(client, server.sessions, Route.Chat(AgentId("agent"), SessionId("other"))))
            unrelated.chat.start()
            ViewModelStore().apply { put("voice", unrelated); clear() }
            val dictation = DictationViewModel(engine)
            dictation.toggle()
            dictation.stop()
            assertFalse(dictation.state.value.dictating)
            assertEquals(VoicePhase.Listening, owned.state.value.phase)
            owned.pause()
            runCurrent()
            frames.close()
            frames = Channel(Channel.UNLIMITED)
            advanceTimeBy(1_000); runCurrent()
            assertTrue(client.state.value is ConnectionState.Connected)
            assertEquals("The connection to the Server dropped.", owned.state.value.error)
            assertEquals(VoicePhase.Off, owned.state.value.phase)
            owned.end()

        } finally {
            server.close(); client.close(); Dispatchers.resetMain()
        }
    }
}
