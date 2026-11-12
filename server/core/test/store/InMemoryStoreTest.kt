package kinuvo.server.store

import kinuvo.protocol.withWrittenAt
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryStoreTest {
    @Test
    fun batchesReconcilePartialSavesAndOwnersRemainIsolated() = runTest {
        val store = InMemoryStore { 123L }
        val session = store.createSession("external", "")
        val user = SessionEntry.UserMessage("same", submissionId = "first")
        val second = user.copy(submissionId = "second")
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        store.append("external", session.id, user)
        store.append("external", session.id, SessionEntry.Details("Renamed"))
        assertEquals(listOf(second, reply), store.appendAll("external", session.id, listOf(user, second, reply)).map { it.withWrittenAt(null) })
        val saved = store.entries("external", session.id)
        assertEquals(listOf(123L, 123L, 123L, 123L, 123L), saved.map { it.writtenAt })
        assertEquals(emptyList(), store.appendAll("external", session.id, listOf(user, second, reply)).map { it.withWrittenAt(null) })
        assertFailsWith<SessionCommitConflict> { store.appendAll("external", session.id, listOf(reply.copy(text = "different"))) }
        assertEquals(saved, store.entries("external", session.id))
        assertEquals(emptyList(), store.sessions("other"))
        assertFailsWith<IllegalArgumentException> { store.append("other", session.id, user) }
        assertFailsWith<IllegalArgumentException> { store.deleteSession("other", session.id) }
        assertEquals("Renamed", store.session("external", session.id).title)
    }
}
