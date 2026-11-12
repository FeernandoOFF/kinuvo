package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.server.store.Store
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionLogTest {
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
        assertEquals(1, updates.count { it.change is SessionChange.Entry })
        val committed = (updates.single { it.change is SessionChange.Entry }.change as SessionChange.Entry).entry
        assertEquals(123L, committed.writtenAt)
        assertEquals(committed, log.history(AgentId("external"), session, 100, 100).last().entry)
        assertEquals(committed, log.entries(AgentId("external"), session).last())
        val entryIndex = updates.indexOfFirst { it.change is SessionChange.Entry }
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
}
