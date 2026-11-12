package kinuvo.app

import kinuvo.app.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import kotlin.test.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

class LiveCallsTest {
    private class Chat : VoiceChat {
        override val state = MutableStateFlow(VoiceChatState())
        var ends = 0
        override fun start() { state.value = VoiceChatState(phase = VoicePhase.Listening, live = true) }
        override fun end() { ends++; state.value = VoiceChatState() }
        override fun pause() { state.value = state.value.copy(paused = true) }
        override fun resume() { state.value = state.value.copy(paused = false) }
        override fun speaker(on: Boolean) { state.value = state.value.copy(speaker = on) }
        override fun hold() {}
        override fun letGo() {}
        override fun background() {}
        override fun foreground() {}
    }

    @Test fun failedCallStaysLiveUntilEnded() = runTest {
        val calls = LiveCalls(backgroundScope)
        val failed = Chat()
        calls.start(AgentId("agent"), SessionId("one"), failed)
        runCurrent()
        failed.state.value = VoiceChatState(error = "The call dropped.", live = true)
        advanceTimeBy(5_000); runCurrent()
        val call = assertNotNull(calls.state.value)
        assertEquals("The call dropped.", call.voice.error)
        assertTrue(call.voice.live)
        calls.end()
        assertNull(calls.state.value)
        assertEquals(1, failed.ends)

        val next = Chat()
        calls.start(AgentId("agent"), SessionId("one"), next)
        runCurrent()
        assertEquals(VoicePhase.Listening, calls.state.value?.voice?.phase)
        assertNull(calls.state.value?.voice?.error)
    }

    @Test fun failedStartStaysLiveUntilEnded() = runTest {
        val calls = LiveCalls(backgroundScope)
        val screen = calls.attach(AgentId("agent"), null, backgroundScope, { error("Pick an Agent first") }) { _, _ -> Chat() }
        screen.start()
        runCurrent()
        assertEquals(VoiceChatState(error = "Pick an Agent first", live = true), screen.state.value)
        screen.end()
        assertEquals(VoiceChatState(), screen.state.value)
    }

    @Test fun pcmLevelsAreNormalized() {
        assertEquals(0f, pcmLevel(byteArrayOf(0, 0)))
        assertEquals(0.5f, pcmLevel(byteArrayOf(0, 64)))
        assertEquals(1f, pcmLevel(byteArrayOf(0, -128)))
    }

    @Test fun oneCallSurvivesDestinationAndSwitchesSessions() = runTest {
        val calls = LiveCalls(backgroundScope)
        val first = Chat()
        calls.start(AgentId("agent"), SessionId("one"), first)
        runCurrent()
        val screenScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val screen = calls.attach(AgentId("agent"), SessionId("one"), screenScope, { AgentId("agent") to SessionId("one") }) { _, _ -> error("Already active") }
        runCurrent()
        screen.background()
        screenScope.cancel()
        assertEquals(VoicePhase.Listening, calls.state.value?.voice?.phase)
        calls.start(AgentId("agent"), SessionId("one"), Chat())
        assertEquals(0, first.ends)
        val next = Chat()
        calls.start(AgentId("agent"), SessionId("two"), next)
        runCurrent()
        assertEquals(1, first.ends)
        assertEquals("two", calls.state.value?.sessionId?.value)
        calls.mute(true)
        runCurrent()
        assertEquals(true, calls.state.value?.voice?.paused)
        calls.end()
        assertNull(calls.state.value)
        assertEquals(1, next.ends)
    }
}
