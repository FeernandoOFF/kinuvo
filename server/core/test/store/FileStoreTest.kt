package kinuvo.server.store

import kinuvo.server.plugin.appendText
import kinuvo.server.plugin.exists
import kinuvo.server.plugin.readText
import kinuvo.server.plugin.writeAtomically
import kinuvo.server.TestHome
import kinuvo.harness.builtin.activeHistory
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.withWrittenAt
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.session.ToolCallId
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val session = store.createSession(AgentId("external"), "")
        assertEquals(false, home.files.config(AgentId("external")).exists())
        assertEquals(listOf(home.agent.id), store.agents().map { it.id })
        val user = SessionEntry.UserMessage("same", submissionId = "submission")
        val reply = SessionEntry.AssistantMessage("reply", messageId = "reply")
        val call = SessionEntry.ToolCall(ToolCallId("tool"), "read", "{}")
        val result = SessionEntry.ToolResult(ToolCallId("tool"), "read", "done")
        store.append(AgentId("external"), session.id, user)
        val details = SessionEntry.Details("Renamed", true)
        store.append(AgentId("external"), session.id, details)
        val batch = listOf(user, call, result, reply)
        assertEquals(listOf(call, result, reply), store.appendAll(AgentId("external"), session.id, batch).map { it.withWrittenAt(null) })
        assertEquals(emptyList(), store.appendAll(AgentId("external"), session.id, batch).map { it.withWrittenAt(null) })
        val saved = store.entries(AgentId("external"), session.id)
        assertEquals(listOf(SessionEntry.Started(""), user, details, call, result, reply), saved.map { it.withWrittenAt(null) })
        assertFailsWith<SessionCommitConflict> {
            store.appendAll(AgentId("external"), session.id, listOf(SessionEntry.Notice("new", "gap", "gap"), reply.copy(text = "changed")))
        }
        assertEquals(saved, store.entries(AgentId("external"), session.id))
        val notice = SessionEntry.Notice("notice", "gap", "Missing history", draft = listOf(reply))
        store.appendAll(AgentId("external"), session.id, listOf(notice))
        assertEquals(listOf(user, call, result, reply), store.entries(AgentId("external"), session.id).activeHistory().map { it.withWrittenAt(null) })
        assertEquals("Renamed", store.session(AgentId("external"), session.id).title)
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
        assertFailsWith<IllegalArgumentException> { store.sessions(AgentId("../outside")) }
        assertFailsWith<IllegalArgumentException> { store.createSession(AgentId("../outside"), "") }
        assertFailsWith<IllegalArgumentException> { store.session(AgentId("../outside"), SessionId("safe")) }
        assertFailsWith<IllegalArgumentException> { store.appendAll(AgentId("../outside"), SessionId("safe"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { store.deleteSession(AgentId("../outside"), SessionId("safe")) }
        assertFailsWith<IllegalArgumentException> { store.truncate(AgentId("safe"), SessionId("../outside"), 0) }
    }

    @Test
    fun uuidLegacyIdsHaveNoFabricatedDate() = runTest {
        val home = TestHome()
        val id = "b55ae592-d468-49ad-ac8b-14a79c1db576"
        home.store.append(AgentId("external"), SessionId(id), SessionEntry.Started(""))
        assertEquals(null, home.store.session(AgentId("external"), SessionId(id)).createdAt)
        assertEquals(0L, home.store.sessions(AgentId("external")).single().legacyOrder)
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
        assertTrue(first.id.value.endsWith("-1234"))
        assertEquals(first.id.value.dropLast(4) + "5678", second.id.value)
        assertEquals(first.id.value.dropLast(4) + "9abc", third.id.value)
        assertEquals(setOf(second.id, third.id), store.sessions(home.agent.id).map { it.id }.toSet())
        assertEquals(listOf(first.id), store.sessions(other.id).map { it.id })
    }

    @Test
    fun routineSessionJoinsTheChatsOnceItReportsOrTheUserJoinsIn() = runTest {
        val home = TestHome()
        val routine = kinuvo.protocol.routine.RoutineRef("watch", "Watch")
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
        home.store.append(home.agent.id, session.id, SessionEntry.UserMessage(kinuvo.protocol.session.taskPrompt("t1", "Renew passport") + "\nDue: 2026-10-09"))
        val info = home.store.session(home.agent.id, session.id)
        assertEquals("Renew passport", info.title)
        assertEquals("t1", info.task)
    }

    @Test
    fun bootstrapsDefaultAgent() {
        val agent = TestHome().agent
        assertEquals("default", agent.id.value)
        assertEquals("Kinuvo", agent.name)
        assertEquals("codex", agent.config.provider)
    }

    @Test
    fun clonesSettingsButNotMemory() = runTest {
        val home = TestHome()
        home.docs.write(AgentId("default"), kinuvo.harness.AgentDoc.Memory, "- secret")
        writeAtomically(Path(home.files.skills(AgentId("default")), "notes", "SKILL.md"), "x")
        val clone = home.store.createAgent("work", home.agent)
        assertEquals("Kinuvo", clone.name)
        assertEquals("", home.docs.read(AgentId("work"), kinuvo.harness.AgentDoc.Memory))
        assertEquals("x", Path(home.files.skills(AgentId("work")), "notes", "SKILL.md").readText())
    }

    @Test
    fun deletesAnAgent() = runTest {
        val home = TestHome()
        val agent = home.store.createAgent("work")
        home.store.deleteAgent(agent)
        assertEquals(false, home.files.agent(AgentId("work")).exists())
    }

    @Test
    fun rejectsSessionPathTraversal() = runTest {
        val home = TestHome()
        val agent = home.agent
        val outside = Path(home.files.agent(agent.id), "outside.jsonl")
        assertFailsWith<IllegalArgumentException> { home.store.entries(agent.id, SessionId("../outside")) }
        assertFailsWith<IllegalArgumentException> { home.store.append(agent.id, SessionId("../outside"), SessionEntry.UserMessage("unsafe")) }
        assertEquals(false, outside.exists())
    }

    @Test
    fun roundTripsSessionEntries() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent
        val session = store.createSession(agent.id, "be nice")
        store.append(agent.id, session.id, SessionEntry.UserMessage("hello there"))
        store.append(agent.id, session.id, SessionEntry.ToolCall(ToolCallId("c1"), "memory", """{"a":1}"""))

        assertEquals(
            listOf(
                SessionEntry.Started("be nice"),
                SessionEntry.UserMessage("hello there"),
                SessionEntry.ToolCall(ToolCallId("c1"), "memory", """{"a":1}"""),
            ),
            store.entries(agent.id, session.id).map { it.withWrittenAt(null) },
        )
        assertEquals("hello there", store.sessions(agent.id).single().title)
        store.truncate(agent.id, session.id, 1)
        assertEquals(listOf(SessionEntry.Started("be nice")), store.entries(agent.id, session.id).map { it.withWrittenAt(null) })
    }

    private suspend fun assertMatchesFreshStore(home: TestHome, store: FileStore, ids: List<SessionId>) {
        val fresh = FileStore(home.files)
        assertEquals(fresh.sessions(home.agent.id), store.sessions(home.agent.id))
        ids.forEach {
            assertEquals(fresh.session(home.agent.id, it), store.session(home.agent.id, it))
            assertEquals(fresh.entries(home.agent.id, it).size, store.entryCount(home.agent.id, it))
        }
    }

    @Test
    fun cachedSummariesFollowEveryWritePath() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent.id
        val first = store.createSession(agent, "").id
        val second = store.createSession(agent, "").id
        assertMatchesFreshStore(home, store, listOf(first, second))
        store.append(agent, first, SessionEntry.UserMessage("Hello there"))
        store.append(agent, first, SessionEntry.Settings(SessionSettings(model = "m")))
        store.append(agent, first, SessionEntry.Details("Named", true))
        assertMatchesFreshStore(home, store, listOf(first, second))
        store.appendAll(agent, second, listOf(SessionEntry.UserMessage("Other", submissionId = "s"), SessionEntry.AssistantMessage("r", "r")))
        assertMatchesFreshStore(home, store, listOf(first, second))
        store.truncate(agent, first, 2)
        assertMatchesFreshStore(home, store, listOf(first, second))
        assertEquals(2, store.entryCount(agent, first))
        store.deleteSession(agent, second)
        assertEquals(listOf(first), store.sessions(agent).map { it.id })
        assertEquals(0, store.entryCount(agent, second))
        assertMatchesFreshStore(home, store, listOf(first))
    }

    @Test
    fun editsMadeBehindTheStoreAreSeen() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent.id
        val kept = store.createSession(agent, "").id
        val removed = store.createSession(agent, "").id
        store.append(agent, kept, SessionEntry.UserMessage("Original"))
        assertEquals("Original", store.session(agent, kept).title)
        assertEquals(2, store.entryCount(agent, kept))
        val file = Path(home.files.sessions(agent), "$kept.jsonl")
        file.appendText("""{"type":"details","title":"Edited by hand","favorite":true}""" + "\n")
        assertEquals("Edited by hand", store.session(agent, kept).title)
        assertEquals(3, store.entryCount(agent, kept))
        writeAtomically(file, """{"type":"started","instructions":"restored"}""" + "\n")
        assertEquals(1, store.entryCount(agent, kept))
        assertEquals("New session", store.session(agent, kept).title)
        SystemFileSystem.delete(Path(home.files.sessions(agent), "$removed.jsonl"))
        assertEquals(0, store.entryCount(agent, removed))
        assertEquals(listOf(kept), store.sessions(agent).map { it.id })
        assertMatchesFreshStore(home, store, listOf(kept))
    }

    @Test
    fun corruptFilesAreNeverCachedAndHealWhenFixed() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent.id
        val id = store.createSession(agent, "").id
        store.append(agent, id, SessionEntry.UserMessage("fine"))
        assertEquals(2, store.entryCount(agent, id))
        val file = Path(home.files.sessions(agent), "$id.jsonl")
        file.appendText("{\"type\":")
        assertEquals(true, assertFailsWith<CorruptSession> { store.entryCount(agent, id) }.trailing)
        assertFailsWith<CorruptSession> { store.session(agent, id) }
        assertFailsWith<CorruptSession> { store.append(agent, id, SessionEntry.UserMessage("more")) }
        file.appendText("\n")
        assertEquals(false, assertFailsWith<CorruptSession> { store.entryCount(agent, id) }.trailing)
        writeAtomically(file, """{"type":"user","text":"fine"}""" + "\n")
        assertEquals(1, store.entryCount(agent, id))
    }

    @Test
    fun appendAfterAnUnterminatedValidLineStartsANewLine() = runTest {
        val home = TestHome()
        val store = home.store
        val agent = home.agent.id
        val id = store.createSession(agent, "").id
        val file = Path(home.files.sessions(agent), "$id.jsonl")
        writeAtomically(file, """{"type":"user","text":"no newline"}""")
        assertEquals(1, store.entryCount(agent, id))
        store.append(agent, id, SessionEntry.UserMessage("next"))
        store.append(agent, id, SessionEntry.UserMessage("last"))
        assertEquals(listOf("no newline", "next", "last"), FileStore(home.files).entries(agent, id).map { (it as SessionEntry.UserMessage).text })
        assertEquals(3, store.entryCount(agent, id))
    }

    @Test
    fun anotherAgentsSessionStillFailsAndNothingIsReadTwice() = runTest {
        val home = TestHome()
        val store = home.store
        val other = store.createAgent("other")
        val id = store.createSession(home.agent.id, "").id
        assertFailsWith<IllegalArgumentException> { store.entryCount(other.id, id) }
        assertFailsWith<IllegalArgumentException> { store.append(other.id, id, SessionEntry.UserMessage("x")) }
        store.append(home.agent.id, id, SessionEntry.UserMessage("x"))
        val before = store.transcriptReads
        repeat(50) {
            store.entryCount(home.agent.id, id)
            store.session(home.agent.id, id)
            store.append(home.agent.id, id, SessionEntry.AssistantMessage("a$it", "a$it"))
        }
        repeat(3) { store.sessions(home.agent.id) }
        assertEquals(before, store.transcriptReads)
        assertEquals(52, store.entryCount(home.agent.id, id))
        assertFalse(FileStore(home.files).entries(home.agent.id, id).isEmpty())
    }
}
