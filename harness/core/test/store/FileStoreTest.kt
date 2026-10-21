package kinuvo.harness.core.store

import kinuvo.harness.core.TestHome
import kinuvo.harness.core.activeHistory
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileStoreTest {
    @Test
    fun obsoleteCompactionsDoNotMakeSessionsUnloadable() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "instructions")
        val file = Path(home.files.sessions(home.agent.id), "${session.id}.jsonl")
        file.appendText("""{"type":"compaction","summary":"old","keepFrom":2,"activations":[],"user":null}""" + "\n")
        file.appendText("""{"type":"compaction","futureField":true}""" + "\n")
        home.store.append(home.agent, session.id, SessionEntry.UserMessage("next"))
        val entries = home.store.entries(home.agent, session.id)
        assertEquals(4, entries.size)
        assertEquals(listOf(SessionEntry.UserMessage("next")), entries.activeHistory())
    }

    @Test
    fun routineSessionJoinsTheChatsOnceItReportsOrTheUserJoinsIn() = runTest {
        val home = TestHome()
        val routine = kinuvo.protocol.RoutineRef("watch", "Watch")
        val quiet = home.store.createSession(home.agent, "instructions", routine)
        home.store.append(home.agent, quiet.id, SessionEntry.Outcome(report = false))
        assertEquals(false, home.store.session(home.agent, quiet.id).inChats)
        home.store.append(home.agent, quiet.id, SessionEntry.UserMessage("what changed?"))
        assertEquals(true, home.store.session(home.agent, quiet.id).inChats)
        val reported = home.store.createSession(home.agent, "instructions", routine)
        home.store.append(home.agent, reported.id, SessionEntry.UserMessage("Check", routine))
        assertEquals(false, home.store.session(home.agent, reported.id).inChats)
        home.store.append(home.agent, reported.id, SessionEntry.Outcome(report = true, summary = "Something changed"))
        assertEquals(true, home.store.session(home.agent, reported.id).inChats)
        home.store.truncate(home.agent, reported.id, 2)
        assertEquals(false, home.store.session(home.agent, reported.id).inChats)
    }

    @Test
    fun taskRunIsTitledByItsTask() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "instructions")
        home.store.append(home.agent, session.id, SessionEntry.UserMessage(kinuvo.protocol.taskPrompt("t1", "Renew passport") + "\nDue: 2026-10-09"))
        val info = home.store.session(home.agent, session.id)
        assertEquals("Renew passport", info.title)
        assertEquals("t1", info.task)
    }

    @Test
    fun bootstrapsDefaultAgent() {
        val agent = TestHome().agent
        assertEquals("default", agent.id)
        assertEquals("Kinuvo", agent.name)
        assertEquals("codex", agent.config.provider)
    }

    @Test
    fun clonesSettingsButNotMemory() = runTest {
        val home = TestHome()
        home.docs.write("default", kinuvo.harness.core.AgentDoc.Memory, "- secret")
        writeAtomically(Path(home.files.skills("default"), "notes", "SKILL.md"), "x")
        val clone = home.store.createAgent("work", home.agent)
        assertEquals("Kinuvo", clone.name)
        assertEquals("", home.docs.read("work", kinuvo.harness.core.AgentDoc.Memory))
        assertEquals("x", Path(home.files.skills("work"), "notes", "SKILL.md").readText())
    }

    @Test
    fun deletesAnAgent() = runTest {
        val home = TestHome()
        val agent = home.store.createAgent("work")
        home.store.deleteAgent(agent)
        assertEquals(false, home.files.agent("work").exists())
    }

    @Test
    fun rejectsSessionPathTraversal() = runTest {
        val home = TestHome()
        val agent = home.agent
        val outside = Path(home.files.agent(agent.id), "outside.jsonl")
        assertFailsWith<IllegalArgumentException> { home.store.entries(agent, "../outside") }
        assertFailsWith<IllegalArgumentException> { home.store.append(agent, "../outside", SessionEntry.UserMessage("unsafe")) }
        assertEquals(false, outside.exists())
    }

    @Test
    fun roundTripsSessionEntries() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent
        val session = store.createSession(agent, "be nice")
        store.append(agent, session.id, SessionEntry.UserMessage("hello there"))
        store.append(agent, session.id, SessionEntry.ToolCall("c1", "memory", """{"a":1}"""))

        assertEquals(
            listOf(
                SessionEntry.Started("be nice"),
                SessionEntry.UserMessage("hello there"),
                SessionEntry.ToolCall("c1", "memory", """{"a":1}"""),
            ),
            store.entries(agent, session.id),
        )
        assertEquals("hello there", store.sessions(agent).single().title)
        store.truncate(agent, session.id, 1)
        assertEquals(listOf(SessionEntry.Started("be nice")), store.entries(agent, session.id))
    }
}
