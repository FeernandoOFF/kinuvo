package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.store.Store
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionChange
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionUpdate
import kinuvo.harness.core.store.SessionCommitConflict
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kinuvo.protocol.SessionSettings
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
        val id = log.createSession("external", "").id
        log.append("external", id, SessionEntry.UserMessage("Automatic title"))
        log.updateDetails("external", id, "  Saved title  ", true)
        assertEquals("Saved title", log.session("external", id).title)
        assertEquals("x".repeat(MAX_SESSION_TITLE), log.updateDetails("external", id, "x".repeat(120), null).title)
        assertEquals("Automatic title", log.updateDetails("external", id, "   ", null).title)
        assertEquals("Automatic title", log.updateDetails("external", id, null, true).title)
        assertEquals(true, log.updateDetails("external", id, "Renamed", null).favorite)
        val favorite = log.updateDetails("external", id, null, false)
        assertEquals("Renamed", favorite.title)
        assertEquals(false, favorite.favorite)
    }

    @Test
    fun batchPublishesOnceBeforeTerminalAndBrowserCleanupPrecedesRemoval() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val session = log.createSession("external", "").id
        val updates = mutableListOf<SessionUpdate>()
        log.subscribe("external", session, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { updates += update; return true }
        })
        updates.clear()
        log.publish("external", session, AgentEvent.RunStarted("run"))
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        assertEquals(listOf(reply), log.appendAll("external", session, listOf(reply)))
        assertEquals(emptyList(), log.appendAll("external", session, listOf(reply)))
        log.publish("external", session, AgentEvent.RunFinished("run"))
        assertEquals(1, updates.count { it.change is SessionChange.Entry })
        val entryIndex = updates.indexOfFirst { it.change is SessionChange.Entry }
        val finishedIndex = updates.indexOfFirst { (it.change as? SessionChange.Event)?.event is AgentEvent.RunFinished }
        assertTrue(entryIndex in 0 until finishedIndex)
        assertFailsWith<SessionCommitConflict> { log.appendAll("external", session, listOf(reply.copy(text = "conflict"))) }
        var cleaned = false
        log.onDelete = { owner, id ->
            assertEquals("external", owner)
            assertEquals(session, id)
            assertTrue(log.entries(owner, id).isNotEmpty())
            cleaned = true
        }
        log.deleteSession("external", session)
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
                override suspend fun truncate(agentId: String, sessionId: String, size: Int) {
                    truncating.complete(Unit)
                    resume.await()
                    files.truncate(agentId, sessionId, size)
                }
            }
            val log = SessionLog(store)
            val agent = Agent("test", "Test", AgentConfig())
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
            assertEquals(before + first + latest, log.entries(agent.id, session))
            assertEquals(latest.settings, log.session(agent.id, session).settings)
        }
    }
}
