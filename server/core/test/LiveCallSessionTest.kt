package kinuvo.server

import kinuvo.protocol.voice.LiveCallPhase
import kinuvo.protocol.voice.LiveCallUpdate
import kinuvo.protocol.voice.LivePeerState
import kinuvo.server.plugin.LiveCall
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.LiveCallException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveCallSessionTest {
    private class Call(val failure: LiveCallException? = null) : LiveCall {
        var ran = false
        val finished = CompletableDeferred<Unit>()
        override suspend fun run(onEvent: suspend (LiveCallEvent) -> Unit) {
            ran = true
            onEvent(LiveCallEvent.Ready)
            failure?.let { throw it }
            finished.await()
        }
        override suspend fun reply(id: String, text: String) {}
        override suspend fun notify(text: String) {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun end() { finished.complete(Unit) }
    }

    @Test fun aCallTheDeviceNeverAttachesEndsWithAnError() = runTest {
        val call = Call()
        val updates = mutableListOf<LiveCallUpdate>()
        val session = LiveCallSession(call, this, { updates += it; true }, { _, _ -> "" }, attachTimeoutMillis = 1_000)
        session.start()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(session.active)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(session.active)
        assertFalse(call.ran)
        assertEquals(listOf<LiveCallUpdate>(LiveCallUpdate.Ended(session.id, "The call never connected.")), updates)
    }

    @Test fun theProvidersFailureEndsTheCallWithItsMessage() = runTest {
        val updates = mutableListOf<LiveCallUpdate>()
        val session = LiveCallSession(Call(LiveCallException("The call dropped.")), this, { updates += it; true }, { _, _ -> "" })
        session.start()
        session.peer(LivePeerState.Connecting)
        runCurrent()
        assertEquals(listOf(LiveCallUpdate.Phase(session.id, LiveCallPhase.Listening), LiveCallUpdate.Ended(session.id, "The call dropped.")), updates)
    }

    @Test fun aClosedPeerEndsTheCallWithoutAnError() = runTest {
        val updates = mutableListOf<LiveCallUpdate>()
        val session = LiveCallSession(Call(), this, { updates += it; true }, { _, _ -> "" })
        session.start()
        session.peer(LivePeerState.Connected)
        runCurrent()
        session.peer(LivePeerState.Closed)
        runCurrent()
        assertEquals(LiveCallUpdate.Ended(session.id), updates.last())
        assertFalse(session.active)
    }

    @Test fun aDeviceThatStopsReceivingEndsTheCall() = runTest {
        val call = Call()
        val session = LiveCallSession(call, this, { it !is LiveCallUpdate.Phase }, { _, _ -> "" })
        session.start()
        session.peer(LivePeerState.Connecting)
        runCurrent()
        assertFalse(session.active)
    }

    @Test fun aFailureSavingTheTranscriptStillEndsTheCallWithAnError() = runTest {
        val updates = mutableListOf<LiveCallUpdate>()
        var released = false
        val call = object : LiveCall {
            override suspend fun run(onEvent: suspend (LiveCallEvent) -> Unit) { onEvent(LiveCallEvent.Transcript("u1", "hello", true)) }
            override suspend fun reply(id: String, text: String) {}
            override suspend fun notify(text: String) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun end() {}
        }
        val session = LiveCallSession(call, this, { updates += it; true }, { _, _ -> "" }, { error("store unavailable") }, { AutoCloseable { released = true } })
        session.start()
        session.peer(LivePeerState.Connecting)
        runCurrent()
        assertTrue(released)
        assertFalse(session.active)
        assertEquals(LiveCallUpdate.Ended(session.id, "The call transcript couldn't be saved."), updates.last())
        assertEquals(1, updates.count { it is LiveCallUpdate.Ended })
    }

    @Test fun theCallIsNoLongerActiveWhenItsEndIsAnnounced() = runTest {
        var activeAtEnd: Boolean? = null
        lateinit var session: LiveCallSession
        session = LiveCallSession(Call(), this, { if (it is LiveCallUpdate.Ended) activeAtEnd = session.active; true }, { _, _ -> "" })
        session.start()
        session.peer(LivePeerState.Connecting)
        runCurrent()
        session.peer(LivePeerState.Closed)
        runCurrent()
        assertEquals(false, activeAtEnd)
    }
}
