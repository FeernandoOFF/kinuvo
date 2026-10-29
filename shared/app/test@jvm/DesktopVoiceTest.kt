package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.Route
import kinuvo.app.session.*
import kinuvo.client.KinuvoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopVoiceTest {
    private class Engine : SpeechEngine {
        override val halfDuplex = true
        var listens = 0
        var stops = 0
        var authorized: ((String?) -> Unit)? = null
        var final: (String) -> Unit = {}
        var finished: ((String) -> Unit)? = null
        override fun authorize(completion: (String?) -> Unit) { authorized = completion }
        override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) { listens++; final = onFinal }
        override fun finish(text: String, onFinal: (String) -> Unit, onError: (String) -> Unit) { finished = onFinal }
        override fun stop() { stops++ }
        override fun speak(text: String, onDone: () -> Unit) = onDone()
        override fun stopSpeaking() {}
    }

    @Test fun halfDuplexReleasesMicrophoneWhileWaitingAndIgnoresAuthorizationAfterEnd() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = Unit
        }
        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val client = KinuvoClient("ws://unused", "test")
        owner.open(client, "test")
        val engine = Engine()
        val session = SessionViewModel(client, owner.sessions, Route.Chat(agentId = "one"))
        val voice = SpeechVoiceChat(engine, session, backgroundScope)
        try {
            voice.start()
            engine.authorized!!(null)
            assertEquals(1, engine.listens)
            engine.final("Hello")
            assertEquals(VoicePhase.Thinking, voice.state.value.phase)
            assertEquals(1, engine.listens)
            assertTrue(engine.stops > 0)
            voice.end()
            voice.start()
            voice.end()
            engine.authorized!!(null)
            assertEquals(1, engine.listens)
            assertEquals(VoicePhase.Off, voice.state.value.phase)
        } finally {
            voice.end(); owner.close(); client.close(); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun holdingToTalkWhilePausedSendsTheTurn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = Unit
        }
        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val client = KinuvoClient("ws://unused", "test")
        owner.open(client, "test")
        val engine = Engine()
        val session = SessionViewModel(client, owner.sessions, Route.Chat(agentId = "one"))
        val voice = SpeechVoiceChat(engine, session, backgroundScope)
        try {
            voice.start()
            engine.authorized!!(null)
            voice.pause()
            voice.hold()
            assertTrue(voice.state.value.holding)
            voice.letGo()
            assertEquals(VoicePhase.Thinking, voice.state.value.phase)
            val stops = engine.stops
            engine.finished!!("Server words")
            assertEquals(VoicePhase.Thinking, voice.state.value.phase)
            assertTrue(engine.stops > stops, "The paused turn must be sent")
        } finally {
            voice.end(); owner.close(); client.close(); runCurrent(); Dispatchers.resetMain()
        }
    }
}
