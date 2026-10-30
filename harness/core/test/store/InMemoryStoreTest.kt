package kinuvo.harness.core.store

import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryStoreTest {
    @Test
    fun batchesReconcilePartialSavesAndOwnersRemainIsolated() = runTest {
        val store = InMemoryStore()
        val session = store.createSession("external", "")
        val user = SessionEntry.UserMessage("same", submissionId = "first")
        val second = user.copy(submissionId = "second")
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        store.append("external", session.id, user)
        store.append("external", session.id, SessionEntry.Details("Renamed"))
        assertEquals(listOf(second, reply), store.appendAll("external", session.id, listOf(user, second, reply)))
        val saved = store.entries("external", session.id)
        assertEquals(emptyList(), store.appendAll("external", session.id, listOf(user, second, reply)))
        assertFailsWith<SessionCommitConflict> { store.appendAll("external", session.id, listOf(reply.copy(text = "different"))) }
        assertEquals(saved, store.entries("external", session.id))
        assertEquals(emptyList(), store.sessions("other"))
        assertFailsWith<IllegalArgumentException> { store.append("other", session.id, user) }
        assertFailsWith<IllegalArgumentException> { store.deleteSession("other", session.id) }
        assertEquals("Renamed", store.session("external", session.id).title)
    }
}
