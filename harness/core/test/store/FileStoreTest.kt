package kinuvo.harness.core.store

import kinuvo.harness.core.TestHome
import kinuvo.harness.core.activeHistory
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.withWrittenAt
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.random.Random

class FileStoreTest {
    @Test
    fun writeTimesSurviveReloadAndReplayWhileLegacyEntriesStayUnknown() = runTest {
        val home = TestHome()
        var now = 100L
        val store = FileStore(home.files, { now })
        val session = store.createSession(home.agent.id, "instructions")
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        now = 200L
        assertEquals(200L, store.appendAll(home.agent.id, session.id, listOf(reply)).single().writtenAt)
        now = 300L
        assertEquals(emptyList(), store.appendAll(home.agent.id, session.id, listOf(reply.copy(writtenAt = 999L))))
        val file = Path(home.files.sessions(home.agent.id), "${session.id}.jsonl")
        file.appendText("""{"type":"assistant","text":"legacy"}""" + "\n")
        val reloaded = FileStore(home.files).entries(home.agent.id, session.id)
        assertEquals(listOf(100L, 200L, null), reloaded.map { it.writtenAt })
    }

    @Test
    fun externalBatchIsAtomicIdempotentAndKeepsConcurrentDetails() = runTest {
        val home = TestHome()
        val store = home.store
        val session = store.createSession("external", "")
        assertEquals(false, home.files.config("external").exists())
        assertEquals(listOf(home.agent.id), store.agents().map { it.id })
        val user = SessionEntry.UserMessage("same", submissionId = "submission")
        val reply = SessionEntry.AssistantMessage("reply", messageId = "reply")
        val call = SessionEntry.ToolCall("tool", "read", "{}")
        val result = SessionEntry.ToolResult("tool", "read", "done")
        store.append("external", session.id, user)
        val details = SessionEntry.Details("Renamed", true)
        store.append("external", session.id, details)
        val batch = listOf(user, call, result, reply)
        assertEquals(listOf(call, result, reply), store.appendAll("external", session.id, batch).map { it.withWrittenAt(null) })
        assertEquals(emptyList(), store.appendAll("external", session.id, batch).map { it.withWrittenAt(null) })
        val saved = store.entries("external", session.id)
        assertEquals(listOf(SessionEntry.Started(""), user, details, call, result, reply), saved.map { it.withWrittenAt(null) })
        assertFailsWith<SessionCommitConflict> {
            store.appendAll("external", session.id, listOf(SessionEntry.Notice("new", "gap", "gap"), reply.copy(text = "changed")))
        }
        assertEquals(saved, store.entries("external", session.id))
        val notice = SessionEntry.Notice("notice", "gap", "Missing history", draft = listOf(reply))
        store.appendAll("external", session.id, listOf(notice))
        assertEquals(listOf(user, call, result, reply), store.entries("external", session.id).activeHistory().map { it.withWrittenAt(null) })
        assertEquals("Renamed", store.session("external", session.id).title)
        assertFailsWith<IllegalArgumentException> { store.entries(home.agent.id, session.id) }
    }

    @Test
    fun corruptRecordsFailWithoutDroppingOrOverwritingHistory() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        val file = Path(home.files.sessions(home.agent.id), "${session.id}.jsonl")
        file.appendText("{\"type\":")
        val torn = file.readText()
        val failure = assertFailsWith<CorruptSession> { home.store.session(home.agent.id, session.id) }
        assertEquals(true, failure.trailing)
        assertFailsWith<CorruptSession> { home.store.appendAll(home.agent.id, session.id, listOf(SessionEntry.AssistantMessage("reply", "reply"))) }
        assertEquals(torn, file.readText())
        file.appendText("\n{\"type\":\"user\",\"text\":\"after\"}\n")
        assertEquals(false, assertFailsWith<CorruptSession> { home.store.entries(home.agent.id, session.id) }.trailing)
    }

    @Test
    fun everySessionPathRejectsUnsafeOwnersAndIds() = runTest {
        val store = TestHome().store
        assertFailsWith<IllegalArgumentException> { store.sessions("../outside") }
        assertFailsWith<IllegalArgumentException> { store.createSession("../outside", "") }
        assertFailsWith<IllegalArgumentException> { store.session("../outside", "safe") }
        assertFailsWith<IllegalArgumentException> { store.appendAll("../outside", "safe", emptyList()) }
        assertFailsWith<IllegalArgumentException> { store.deleteSession("../outside", "safe") }
        assertFailsWith<IllegalArgumentException> { store.truncate("safe", "../outside", 0) }
    }

    @Test
    fun uuidLegacyIdsHaveNoFabricatedDate() = runTest {
        val home = TestHome()
        val id = "b55ae592-d468-49ad-ac8b-14a79c1db576"
        home.store.append("external", id, SessionEntry.Started(""))
        assertEquals(null, home.store.session("external", id).createdAt)
        assertEquals(0L, home.store.sessions("external").single().legacyOrder)
    }

    @Test
    fun obsoleteCompactionsDoNotMakeSessionsUnloadable() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "instructions")
        val file = Path(home.files.sessions(home.agent.id), "${session.id}.jsonl")
        file.appendText("""{"type":"compaction","summary":"old","keepFrom":2,"activations":[],"user":null}""" + "\n")
        file.appendText("""{"type":"compaction","futureField":true}""" + "\n")
        home.store.append(home.agent.id, session.id, SessionEntry.UserMessage("next"))
        val entries = home.store.entries(home.agent.id, session.id)
        assertEquals(4, entries.size)
        assertEquals(listOf(SessionEntry.UserMessage("next")), entries.activeHistory().map { it.withWrittenAt(null) })
    }

    @Test
    fun newSessionNeverReusesAnIdTakenByAnotherAgent() = runTest {
        val home = TestHome()
        val other = home.store.createAgent("other")
        val clock = { 1_700_000_000_000L }
        val suffixes = ArrayDeque(listOf(0x1234, 0x1234, 0x5678, 0x5678, 0x9abc))
        val random = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int) = suffixes.removeFirst()
        }
        val store = FileStore(home.files, clock, random)
        val first = store.createSession(other.id, "instructions")
        val second = store.createSession(home.agent.id, "instructions")
        val third = store.createSession(home.agent.id, "instructions")
        assertTrue(first.id.endsWith("-1234"))
        assertEquals(first.id.dropLast(4) + "5678", second.id)
        assertEquals(first.id.dropLast(4) + "9abc", third.id)
        assertEquals(setOf(second.id, third.id), store.sessions(home.agent.id).map { it.id }.toSet())
        assertEquals(listOf(first.id), store.sessions(other.id).map { it.id })
    }

    @Test
    fun routineSessionJoinsTheChatsOnceItReportsOrTheUserJoinsIn() = runTest {
        val home = TestHome()
        val routine = kinuvo.protocol.RoutineRef("watch", "Watch")
        val quiet = home.store.createSession(home.agent.id, "instructions", routine)
        home.store.append(home.agent.id, quiet.id, SessionEntry.Outcome(report = false))
        assertEquals(false, home.store.session(home.agent.id, quiet.id).inChats)
        home.store.append(home.agent.id, quiet.id, SessionEntry.UserMessage("what changed?"))
        assertEquals(true, home.store.session(home.agent.id, quiet.id).inChats)
        val reported = home.store.createSession(home.agent.id, "instructions", routine)
        home.store.append(home.agent.id, reported.id, SessionEntry.UserMessage("Check", routine))
        assertEquals(false, home.store.session(home.agent.id, reported.id).inChats)
        home.store.append(home.agent.id, reported.id, SessionEntry.Outcome(report = true, summary = "Something changed"))
        assertEquals(true, home.store.session(home.agent.id, reported.id).inChats)
        home.store.truncate(home.agent.id, reported.id, 2)
        assertEquals(false, home.store.session(home.agent.id, reported.id).inChats)
    }

    @Test
    fun taskRunIsTitledByItsTask() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "instructions")
        home.store.append(home.agent.id, session.id, SessionEntry.UserMessage(kinuvo.protocol.taskPrompt("t1", "Renew passport") + "\nDue: 2026-10-09"))
        val info = home.store.session(home.agent.id, session.id)
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
        assertFailsWith<IllegalArgumentException> { home.store.entries(agent.id, "../outside") }
        assertFailsWith<IllegalArgumentException> { home.store.append(agent.id, "../outside", SessionEntry.UserMessage("unsafe")) }
        assertEquals(false, outside.exists())
    }

    @Test
    fun roundTripsSessionEntries() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent
        val session = store.createSession(agent.id, "be nice")
        store.append(agent.id, session.id, SessionEntry.UserMessage("hello there"))
        store.append(agent.id, session.id, SessionEntry.ToolCall("c1", "memory", """{"a":1}"""))

        assertEquals(
            listOf(
                SessionEntry.Started("be nice"),
                SessionEntry.UserMessage("hello there"),
                SessionEntry.ToolCall("c1", "memory", """{"a":1}"""),
            ),
            store.entries(agent.id, session.id).map { it.withWrittenAt(null) },
        )
        assertEquals("hello there", store.sessions(agent.id).single().title)
        store.truncate(agent.id, session.id, 1)
        assertEquals(listOf(SessionEntry.Started("be nice")), store.entries(agent.id, session.id).map { it.withWrittenAt(null) })
    }
}
