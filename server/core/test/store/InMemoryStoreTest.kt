package kinuvo.server.store

import kinuvo.protocol.session.withWrittenAt
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryStoreTest {
    @Test
    fun batchesReconcilePartialSavesAndOwnersRemainIsolated() = runTest {
        val store = InMemoryStore { 123L }
        val session = store.createSession(AgentId("external"), "")
        val user = SessionEntry.UserMessage("same", submissionId = "first")
        val second = user.copy(submissionId = "second")
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        store.append(AgentId("external"), session.id, user)
        store.append(AgentId("external"), session.id, SessionEntry.Details("Renamed"))
        assertEquals(listOf(second, reply), store.appendAll(AgentId("external"), session.id, listOf(user, second, reply)).map { it.withWrittenAt(null) })
        val saved = store.entries(AgentId("external"), session.id)
        assertEquals(listOf(123L, 123L, 123L, 123L, 123L), saved.map { it.writtenAt })
        assertEquals(emptyList(), store.appendAll(AgentId("external"), session.id, listOf(user, second, reply)).map { it.withWrittenAt(null) })
        assertFailsWith<SessionCommitConflict> { store.appendAll(AgentId("external"), session.id, listOf(reply.copy(text = "different"))) }
        assertEquals(saved, store.entries(AgentId("external"), session.id))
        assertEquals(emptyList(), store.sessions(AgentId("other")))
        assertFailsWith<IllegalArgumentException> { store.append(AgentId("other"), session.id, user) }
        assertFailsWith<IllegalArgumentException> { store.deleteSession(AgentId("other"), session.id) }
        assertEquals("Renamed", store.session(AgentId("external"), session.id).title)
    }
}
