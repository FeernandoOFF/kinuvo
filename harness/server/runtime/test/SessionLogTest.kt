package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.store.Store
import kinuvo.protocol.SessionEntry
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
    @Test
    fun silentCleanupKeepsSettingsAndSerializesConcurrentChanges() = runBlocking {
        withTimeout(10_000) {
            val files = RuntimeFixture().store
            val truncating = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val store = object : Store by files {
                override suspend fun truncate(agent: Agent, sessionId: String, size: Int) {
                    truncating.complete(Unit)
                    resume.await()
                    files.truncate(agent, sessionId, size)
                }
            }
            val log = SessionLog(store)
            val agent = Agent("test", "Test", AgentConfig())
            val session = log.createSession(agent, "Instructions").id
            val before = log.entries(agent, session)
            val first = SessionEntry.Settings(SessionSettings(model = "first"))
            val latest = SessionEntry.Settings(SessionSettings(model = "latest"))
            log.append(agent, session, SessionEntry.UserMessage("Check"))
            log.append(agent, session, first)
            val cleanup = async { log.discardSilentRun(agent, session, before.size) }
            truncating.await()
            val change = async(start = CoroutineStart.UNDISPATCHED) { log.append(agent, session, latest) }
            assertFalse(change.isCompleted)
            resume.complete(Unit)
            cleanup.await()
            change.await()
            assertEquals(before + first + latest, log.entries(agent, session))
            assertEquals(latest.settings, log.session(agent, session).settings)
        }
    }
}
