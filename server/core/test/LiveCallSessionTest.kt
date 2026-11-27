package kinuvo.server

import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.VoiceTurn
import kinuvo.protocol.voice.LiveCallPhase
import kinuvo.protocol.voice.LiveCallUpdate
import kinuvo.protocol.voice.LivePeerState
import kinuvo.server.plugin.AgentProgress
import kinuvo.server.plugin.ReplyKind
import kinuvo.server.plugin.LiveCall
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.LiveCallException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
        override suspend fun reply(id: String, text: String, kind: ReplyKind) {}
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
            override suspend fun reply(id: String, text: String, kind: ReplyKind) {}
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

    private class Replies : LiveCall {
        val events = Channel<LiveCallEvent>(Channel.UNLIMITED)
        val replies = mutableListOf<Triple<String, String, ReplyKind>>()
        override suspend fun run(onEvent: suspend (LiveCallEvent) -> Unit) { for (event in events) onEvent(event) }
        override suspend fun reply(id: String, text: String, kind: ReplyKind) { replies += Triple(id, text, kind) }
        override suspend fun notify(text: String) {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun end() { events.close() }
    }

    @Test fun progressStaysOffTheCallAndTheAnswerIsFinal() = runTest {
        val call = Replies()
        val session = LiveCallSession(call, this, { true }, { _, progress -> progress(AgentProgress.Text("half way")); "done" })
        session.start()
        session.peer(LivePeerState.Connecting)
        call.events.send(LiveCallEvent.Delegation("d1", "task"))
        runCurrent()
        assertEquals(listOf(Triple("d1", "done", ReplyKind.Final)), call.replies)
        session.end()
        runCurrent()
    }

    @Test fun approvalsAndQuestionsAreSpoken() = runTest {
        val call = Replies()
        val session = LiveCallSession(call, this, { true }, { _, progress ->
            progress(AgentProgress.ApprovalRequested("shell", "rm build"))
            progress(AgentProgress.Question("Which file?"))
            "done"
        })
        session.start()
        session.peer(LivePeerState.Connecting)
        call.events.send(LiveCallEvent.Delegation("d1", "task"))
        runCurrent()
        assertEquals(listOf(ReplyKind.Prompt, ReplyKind.Prompt, ReplyKind.Final), call.replies.map { it.third })
        session.end()
        runCurrent()
    }

    @Test fun aTaskThatFinishesAfterTheCallEndedSpeaksNothing() = runTest {
        val call = Replies()
        val finish = CompletableDeferred<String>()
        val session = LiveCallSession(call, this, { true }, { _, _ -> finish.await() })
        session.start()
        session.peer(LivePeerState.Connecting)
        call.events.send(LiveCallEvent.Delegation("d1", "task"))
        runCurrent()
        session.peer(LivePeerState.Closed)
        runCurrent()
        assertFalse(session.active)
        finish.complete("late answer")
        runCurrent()
        assertEquals(emptyList(), call.replies)
    }

    @Test fun whatWasSaidBeforeADelegationIsSavedBeforeItSoTheSessionReadsInOrder() = runTest {
        val call = Replies()
        val saved = mutableListOf<SessionEntry.VoiceCall>()
        val session = LiveCallSession(call, this, { true }, { _, _ -> "done" }, { saved += it })
        session.start()
        session.peer(LivePeerState.Connecting)
        call.events.send(LiveCallEvent.Transcript("t1", "build me a page", user = true))
        call.events.send(LiveCallEvent.Delegation("d1", "build a page"))
        call.events.send(LiveCallEvent.Transcript("t2", "It is ready", user = false))
        runCurrent()
        assertEquals(listOf(listOf(VoiceTurn(true, "build me a page"))), saved.map { it.turns })
        session.end()
        runCurrent()
        assertEquals(listOf(listOf(VoiceTurn(true, "build me a page")), listOf(VoiceTurn(false, "It is ready"))), saved.map { it.turns })
        assertEquals(saved.size, saved.map { it.id }.toSet().size)
        assertEquals(session.id, saved.first().id)
    }
}
