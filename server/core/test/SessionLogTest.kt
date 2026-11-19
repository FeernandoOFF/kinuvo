package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.server.store.FileStore
import kinuvo.server.store.Store
import kinuvo.protocol.session.QueuedMessage
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.withWrittenAt
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionUpdate
import kinuvo.server.store.SessionCommitConflict
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.RunId
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.routine.RoutineRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kinuvo.protocol.session.SessionStatus
import kinuvo.server.plugin.appendText
import kinuvo.server.store.CorruptSession
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionLogTest {
    @Test fun inboxDetailsSurviveReopeningTheStore() = runBlocking {
        val home = TestHome()
        val agent = home.agent.id
        val log = SessionLog(home.store)
        val id = log.createSession(agent, "").id
        log.updateDetails(agent, id, title = "Saved", favorite = true, settled = true)
        assertTrue(SessionLog(FileStore(home.files)).page(agent, null, 10).sessions.single().settled)

        val run = RunId("run")
        log.publish(agent, id, AgentEvent.RunStarted(run))
        assertFalse(log.session(agent, id).settled)
        log.updateDetails(agent, id, settled = true)
        val interaction = PendingInteraction.Unsupported(InteractionId("question"), "Waiting")
        log.publish(agent, id, AgentEvent.InteractionRequested(interaction))
        assertFalse(log.session(agent, id).settled)
        log.updateDetails(agent, id, settled = true)
        log.append(agent, id, SessionEntry.AssistantMessage("Finished"))
        log.publish(agent, id, AgentEvent.RunFinished(run))

        val reopened = SessionLog(FileStore(home.files))
        val unread = reopened.page(agent, null, 10).sessions.single()
        assertTrue(unread.unread)
        assertTrue(unread.settled)
        assertTrue(unread.favorite)
        assertEquals("Saved", unread.title)
        reopened.updateDetails(agent, id, seen = true)
        assertFalse(SessionLog(FileStore(home.files)).page(agent, null, 10).sessions.single().unread)
    }

    @Test fun onlyReportingRoutineRunsMarkTheSessionUnread() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val agent = AgentId("test")
        val routine = RoutineRef("routine", "Routine")
        val id = log.createSession(agent, "", routine).id
        for (reports in listOf(true, false)) {
            val run = RunId("run-$reports")
            log.publish(agent, id, AgentEvent.RunStarted(run))
            log.append(agent, id, SessionEntry.UserMessage("Run", routine))
            log.append(agent, id, SessionEntry.AssistantMessage("Interim text"))
            log.append(agent, id, SessionEntry.Outcome(reports, ""))
            log.publish(agent, id, AgentEvent.RunFinished(run))
            assertEquals(reports, log.page(agent, null, 10, routine.id).sessions.single().unread)
            log.updateDetails(agent, id, seen = true)
        }
    }

    @Test fun firstChatPageCountsSettledSessionsAcrossPages() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val agent = AgentId("test")
        val routine = RoutineRef("routine", "Routine")
        repeat(2) {
            val id = log.createSession(agent, "").id
            log.updateDetails(agent, id, settled = true)
        }
        val routineOnly = log.createSession(agent, "", routine).id
        log.updateDetails(agent, routineOnly, settled = true)
        val hidden = log.createSession(agent, "").id
        log.append(agent, hidden, SessionEntry.Details(hidden = true, settled = true))

        val first = log.page(agent, null, 1)
        assertEquals(2, first.settledCount)
        assertTrue(first.hasMore)
        assertEquals(0, log.page(agent, first.sessions.single().id.value, 1).settledCount)
        assertEquals(2, log.page(agent, null, 1, routine.id).settledCount)
    }

    @Test fun queuedMessagesReachLiveSubscribersAndReconnectWhileIdle() = runBlocking {
        val fixture = RuntimeFixture()
        val log = SessionLog(fixture.store)
        val id = log.createSession(fixture.agent.id, "").id
        val queue = listOf(QueuedMessage("q", "waiting"))
        log.publish(fixture.agent.id, id, AgentEvent.QueueChanged(queue))
        log.append(fixture.agent.id, id, SessionEntry.UserMessage("previous"))
        val received = mutableListOf<SessionUpdate>()
        log.subscribe(fixture.agent.id, id, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { received += update; return true }
        })
        assertEquals(queue, received.map { it.change }.filterIsInstance<SessionChange.Live>().single().state.queued)
        log.publish(fixture.agent.id, id, AgentEvent.RunStarted(RunId("run")))
        log.publish(fixture.agent.id, id, AgentEvent.RunFinished(RunId("run")))
        received.clear()
        log.publish(fixture.agent.id, id, AgentEvent.QueueChanged(emptyList()))
        assertEquals(AgentEvent.QueueChanged(emptyList()), (received.single().change as SessionChange.Event).event)
    }

    @Test fun metadataUpdatesPreserveEachOther() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession(AgentId("external"), "").id
        log.append(AgentId("external"), id, SessionEntry.UserMessage("Automatic title"))
        log.updateDetails(AgentId("external"), id, "  Saved title  ", true)
        assertEquals("Saved title", log.session(AgentId("external"), id).title)
        assertEquals("x".repeat(MAX_SESSION_TITLE), log.updateDetails(AgentId("external"), id, "x".repeat(120), null).title)
        assertEquals("Automatic title", log.updateDetails(AgentId("external"), id, "   ", null).title)
        assertEquals("Automatic title", log.updateDetails(AgentId("external"), id, null, true).title)
        assertEquals(true, log.updateDetails(AgentId("external"), id, "Renamed", null).favorite)
        val favorite = log.updateDetails(AgentId("external"), id, null, false)
        assertEquals("Renamed", favorite.title)
        assertEquals(false, favorite.favorite)
    }

    @Test
    fun batchPublishesOnceBeforeTerminalAndBrowserCleanupPrecedesRemoval() = runBlocking {
        val log = SessionLog(RuntimeFixture().store) { 123L }
        val session = log.createSession(AgentId("external"), "").id
        val updates = mutableListOf<SessionUpdate>()
        log.subscribe(AgentId("external"), session, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { updates += update; return true }
        })
        updates.clear()
        log.publish(AgentId("external"), session, AgentEvent.RunStarted(RunId("run")))
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        assertEquals(listOf(reply), log.appendAll(AgentId("external"), session, listOf(reply)).map { it.withWrittenAt(null) })
        assertEquals(emptyList(), log.appendAll(AgentId("external"), session, listOf(reply)).map { it.withWrittenAt(null) })
        log.publish(AgentId("external"), session, AgentEvent.RunFinished(RunId("run")))
        val committed = updates.mapNotNull { (it.change as? SessionChange.Entry)?.entry as? SessionEntry.AssistantMessage }.single()
        assertEquals(123L, committed.writtenAt)
        assertEquals(committed, log.history(AgentId("external"), session, 100, 100).map { it.entry }.filterIsInstance<SessionEntry.AssistantMessage>().single())
        assertEquals(committed, log.entries(AgentId("external"), session).filterIsInstance<SessionEntry.AssistantMessage>().single())
        val entryIndex = updates.indexOfFirst { (it.change as? SessionChange.Entry)?.entry == committed }
        val finishedIndex = updates.indexOfFirst { (it.change as? SessionChange.Event)?.event is AgentEvent.RunFinished }
        assertTrue(entryIndex in 0 until finishedIndex)
        assertFailsWith<SessionCommitConflict> { log.appendAll(AgentId("external"), session, listOf(reply.copy(text = "conflict"))) }
        var cleaned = false
        log.onDelete = { owner, id ->
            assertEquals(AgentId("external"), owner)
            assertEquals(session, id)
            assertTrue(log.entries(owner, id).isNotEmpty())
            cleaned = true
        }
        log.deleteSession(AgentId("external"), session)
        assertTrue(cleaned)
        assertFalse(log.watched(session))
    }

    @Test
    fun silentCleanupKeepsSettingsAndSerializesConcurrentChanges() = runBlocking {
        withTimeout(10_000) {
            val files = RuntimeFixture().store
            val truncating = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val store = object : Store by files {
                override suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int) {
                    truncating.complete(Unit)
                    resume.await()
                    files.truncate(agentId, sessionId, size)
                }
            }
            val log = SessionLog(store)
            val agent = Agent(AgentId("test"), "Test", AgentConfig())
            val session = log.createSession(agent.id, "Instructions").id
            val before = log.entries(agent.id, session)
            val first = SessionEntry.Settings(SessionSettings(model = "first"))
            val latest = SessionEntry.Settings(SessionSettings(model = "latest"))
            log.append(agent.id, session, SessionEntry.UserMessage("Check"))
            log.append(agent.id, session, first)
            val cleanup = async { log.discardSilentRun(agent.id, session, before.size) }
            truncating.await()
            val change = async(start = CoroutineStart.UNDISPATCHED) { log.append(agent.id, session, latest) }
            assertFalse(change.isCompleted)
            resume.complete(Unit)
            cleanup.await()
            change.await()
            assertEquals((before + first + latest).map { it.withWrittenAt(null) }, log.entries(agent.id, session).map { it.withWrittenAt(null) })
            assertEquals(latest.settings, log.session(agent.id, session).settings)
        }
    }

    @Test
    fun publishesNeverReadTranscriptsAgainAndKeepPerSessionOrder() = runBlocking {
        val home = TestHome()
        val log = SessionLog(home.store)
        val agent = home.agent.id
        val a = log.createSession(agent, "").id
        val b = log.createSession(agent, "").id
        val seen = mutableMapOf(a to mutableListOf<AgentEvent>(), b to mutableListOf<AgentEvent>())
        for (id in listOf(a, b)) log.subscribe(agent, id, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { (update.change as? SessionChange.Event)?.let { seen.getValue(id) += it.event }; return true }
        })
        log.publish(agent, a, AgentEvent.RunStarted(RunId("a")))
        val reads = home.store.transcriptReads
        repeat(20) {
            log.publish(agent, a, AgentEvent.TextMessageContent("m", "a$it"))
            log.publish(agent, b, AgentEvent.TextMessageContent("m", "b$it"))
        }
        log.publish(agent, a, AgentEvent.RunFinished(RunId("a")))
        assertEquals(reads, home.store.transcriptReads)
        val deltas = { id: SessionId -> seen.getValue(id).filterIsInstance<AgentEvent.TextMessageContent>().map { it.delta } }
        assertEquals(List(20) { "a$it" }, deltas(a))
        assertEquals(List(20) { "b$it" }, deltas(b))
        assertEquals(SessionStatus.Idle, log.status(a))
    }

    @Test
    fun publishToAnotherAgentsSessionFailsAndCorruptionStillSurfaces() = runBlocking {
        val home = TestHome()
        val log = SessionLog(home.store)
        val other = home.store.createAgent("other")
        val id = log.createSession(home.agent.id, "").id
        assertFailsWith<IllegalArgumentException> { log.publish(other.id, id, AgentEvent.RunStarted(RunId("x"))) }
        log.publish(home.agent.id, id, AgentEvent.RunStarted(RunId("x")))
        Path(home.files.sessions(home.agent.id), "$id.jsonl").appendText("{\"type\":")
        assertFailsWith<CorruptSession> { log.publish(home.agent.id, id, AgentEvent.RunFinished(RunId("x"))) }
        Unit
    }

    @Test
    fun pageDoesNotReadEveryTranscriptAgain() = runBlocking {
        val home = TestHome()
        val log = SessionLog(home.store)
        repeat(30) { log.createSession(home.agent.id, "s$it") }
        log.page(home.agent.id, null, 20)
        val reads = home.store.transcriptReads
        assertEquals(20, log.page(home.agent.id, null, 20).sessions.size)
        assertEquals(reads, home.store.transcriptReads)
    }
}
