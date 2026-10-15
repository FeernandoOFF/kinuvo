package kinuvo.harness.core

import kinuvo.protocol.AgentEvent

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionEntry.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.runTest
import kinuvo.harness.core.store.readText
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionTest {
    @Test
    fun onlyTheLatestScreenshotsAreSentAgain() {
        fun shot(n: Int) = ToolResult("$n", "computer_capture", "screen $n", attachments = listOf(kinuvo.protocol.Attachment("$n", "${SCREENSHOT_PREFIX}$n.jpg", "image/jpeg", 10)))
        val file = ToolResult("f", "knowledge", "note", attachments = listOf(kinuvo.protocol.Attachment("f", "plan.pdf", "application/pdf", 10)))
        val pruned = (listOf(file) + (1..5).map(::shot)).recentScreens(keep = 3).filterIsInstance<ToolResult>()
        assertEquals(listOf(1, 0, 0, 1, 1, 1), pruned.map { it.attachments.size })
        assertTrue(pruned[1].output.endsWith("no longer shown.)"))
    }

    @Test
    fun compactionKeepsFilesToolsShowedThisTurn() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 1500))
        val session = home.store.createSession(agent, "")
        val photo = kinuvo.protocol.Attachment("a1", "photo.png", "image/png", 1)
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject) = "x".repeat(4000)
            override suspend fun execute(args: JsonObject, callId: String, run: kinuvo.harness.core.tools.ToolRun) =
                if (args.isEmpty()) execute(args) else { run.attach("photo.png", byteArrayOf(1)); "Attached photo.png" }
        }
        val seen = mutableListOf<List<SessionEntry>>()
        val model = Model { instructions, history, _ ->
            if (instructions == COMPACTION_INSTRUCTIONS) flowOf(StreamFrame.TextDelta("Read a long result."), StreamFrame.End())
            else {
                seen += history
                if (seen.size == 1) flowOf(StreamFrame.ToolCallComplete("old", "read", "{}"),
                    StreamFrame.ToolCallComplete("photo", "read", "{\"file\":true}"), StreamFrame.End())
                else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
            }
        }
        AgentRunner(agent, home.store, model, listOf(tool), attach = { _, _ -> photo }).run(session.id, "What's in the photo?").toList()
        assertTrue(seen.last().first() is Compaction)
        assertEquals(listOf(listOf(photo)), seen.last().filterIsInstance<ToolResult>().filter { it.id == "photo" }.map { it.attachments })
    }

    @Test
    fun compactsLargeExchangesWithinTheFirstTurnAndKeepsTheLatestPair() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 300))
        val session = home.store.createSession(agent, "")
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject) = if (args.isEmpty()) "x".repeat(8000) else "recent result"
        }
        val seen = mutableListOf<List<SessionEntry>>()
        val model = Model { instructions, history, _ ->
            if (instructions == COMPACTION_INSTRUCTIONS) flowOf(StreamFrame.TextDelta("Read the earlier result. Continue the original request."), StreamFrame.End())
            else {
                seen += history
                if (seen.size == 1) flowOf(StreamFrame.ToolCallComplete("old", "read", "{}"),
                    StreamFrame.ToolCallComplete("recent", "read", "{\"recent\":true}"), StreamFrame.End())
                else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
            }
        }
        val events = AgentRunner(agent, home.store, model, listOf(tool), context = "Current Memory").run(session.id, "Original request", Activation("skill", "Skill instructions")).toList()
        assertTrue(events.last() is AgentEvent.RunFinished)
        assertEquals(listOf(UserMessage("Original request")), seen.last().filterIsInstance<UserMessage>())
        assertEquals(listOf("recent"), seen.last().filterIsInstance<ToolCall>().map { it.id })
        assertEquals(listOf("recent"), seen.last().filterIsInstance<ToolResult>().map { it.id })
        assertTrue(seen.last().contains(Context("Current Memory")))
        assertEquals(listOf(Activation("skill", "Skill instructions")), seen.last().filterIsInstance<Compaction>().single().activations)
        assertTrue(home.store.entries(agent, session.id).any { it is ToolResult && it.output.length == 8000 })
    }

    @Test
    fun chunksOversizedSummarizationRequestsAndCompactsAgainWithinTheTurn() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 400))
        val session = home.store.createSession(agent, "")
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject) = "x".repeat(15000)
        }
        var step = 0
        var summaries = 0
        val model = Model { instructions, history, tools ->
            if (instructions == COMPACTION_INSTRUCTIONS) {
                summaries++
                assertTrue(history.estimatedTokens(instructions) <= 768)
                assertTrue(tools.isEmpty())
                flowOf(StreamFrame.TextDelta("Read result. Continue."), StreamFrame.End())
            } else {
                assertEquals(1, history.filterIsInstance<UserMessage>().count { it.text == "Original request" })
                assertTrue(history.estimatedTokens(instructions, tools) <= 768)
                if (++step <= 2) flowOf(StreamFrame.ToolCallComplete("c$step", "read", "{}"), StreamFrame.End())
                else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
            }
        }
        val events = AgentRunner(agent, home.store, model, listOf(tool), contextTokens = 1024).run(session.id, "Original request").toList()
        assertTrue(events.last() is AgentEvent.RunFinished)
        assertEquals(2, events.filterIsInstance<AgentEvent.Compacted>().size)
        assertTrue(summaries > 2)
    }

    @Test
    fun oversizedFreshRequestIsRejectedBeforeCallingTheModel() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "")
        var calls = 0
        val model = Model { _, _, _ -> calls++; flowOf(StreamFrame.End()) }
        val events = AgentRunner(home.agent, home.store, model, emptyList(), contextTokens = 1024).run(session.id, "x".repeat(5000)).toList()
        assertEquals(0, calls)
        assertTrue(events.last() is AgentEvent.RunError)
    }

    @Test
    fun nonReducingSummaryDoesNotReplaceTheActiveHistory() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 50))
        val session = home.store.createSession(agent, "")
        home.store.append(agent, session.id, UserMessage("x".repeat(400)))
        val model = Model { _, _, _ -> flowOf(StreamFrame.TextDelta("y".repeat(1000)), StreamFrame.End()) }
        val events = AgentRunner(agent, home.store, model, emptyList()).run(session.id, "next").toList()
        assertTrue(events.last() is AgentEvent.RunError)
        assertTrue(home.store.entries(agent, session.id).none { it is Compaction })
    }

    @Test
    fun compactionKeepsTheLatestRuntimeContextEvenWhenTheTurnDidNotChangeIt() {
        val context = Context("Current Memory and Workspace")
        val all = listOf(Started("stable"), Context("Old"), UserMessage("old"), context,
            UserMessage("prior"), AssistantMessage("prior reply"), UserMessage("now"), Compaction("summary", 6))
        assertEquals(listOf(Compaction("summary", 6), context, UserMessage("now")), all.activeHistory())
    }

    @Test
    fun activeHistoryStartsAtLatestCompaction() {
        val all = listOf(
            Started("i"), UserMessage("old"), AssistantMessage("old answer"),
            UserMessage("current"), Compaction("sum", keepFrom = 3), AssistantMessage("reply"),
        )
        assertEquals(listOf(Compaction("sum", 3), UserMessage("current"), AssistantMessage("reply")), all.activeHistory())
        assertEquals(listOf(UserMessage("old"), AssistantMessage("old answer")), all.take(3).activeHistory())
    }

    @Test
    fun reflectionActivityStaysOutOfModelHistory() {
        val reflection = SessionEntry.Reflection("r1", 123, kinuvo.protocol.ReflectionResult(error = "Failure"))
        val history = listOf(UserMessage("hi"), reflection, AssistantMessage("hello"))
        assertEquals(listOf(UserMessage("hi"), AssistantMessage("hello")), history.activeHistory())
        val compacted = listOf(Compaction("summary", 1)) + history
        assertEquals(listOf(Compaction("summary", 1), UserMessage("hi"), AssistantMessage("hello")), compacted.activeHistory())
        assertEquals(0, listOf(reflection).estimatedTokens(""))
    }

    @Test
    fun runnerCompactsOlderTurnsWhenOverLimit() = runTest {
        val home = TestHome()
        val store = home.store
        val base = home.agent
        val agent = base.copy(config = base.config.copy(compactAtTokens = 50))
        val session = store.createSession(agent, "")
        store.append(agent, session.id, UserMessage("x".repeat(400)))
        store.append(agent, session.id, AssistantMessage("y".repeat(400)))

        val calls = mutableListOf<Pair<String, List<SessionEntry>>>()
        val model = Model { instructions, history, tools ->
            calls += instructions to history
            if (instructions == COMPACTION_INSTRUCTIONS) {
                assertTrue(tools.isEmpty())
                flowOf(StreamFrame.TextDelta("user sent x's"), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        }

        val events = AgentRunner(agent, store, model, emptyList()).run(session.id, "next").toList()

        assertTrue(events.contains(AgentEvent.Compacted("user sent x's")))
        assertEquals(listOf(UserMessage("x".repeat(400)), AssistantMessage("y".repeat(400))), calls[0].second)
        assertEquals(listOf(Compaction("user sent x's", keepFrom = 3), UserMessage("next")), calls[1].second)
        // Full transcript is kept on disk, nothing duplicated
        assertEquals(1, store.entries(agent, session.id).count { it == UserMessage("next") })
        assertTrue(Path(home.files.sessions(agent.id), "${session.id}.jsonl").readText().contains("\"compaction\""))
    }

    @Test
    fun smallSessionsAreNotCompacted() = runTest {
        val store = TestHome().store
        val agent = store.agents().single()
        val session = store.createSession(agent, "")
        var calls = 0
        val model = Model { _, _, _ -> calls++; flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) }
        AgentRunner(agent, store, model, emptyList()).run(session.id, "hi").toList()
        assertEquals(1, calls)
    }

    @Test
    fun compactionCarriesActivatedSkills() = runTest {
        val home = TestHome()
        val store = home.store
        val base = home.agent
        val agent = base.copy(config = base.config.copy(compactAtTokens = 10))
        val session = store.createSession(agent, "")
        val model = Model { _, _, _ -> flowOf(StreamFrame.TextDelta("summary"), StreamFrame.End()) }
        val runner = AgentRunner(agent, store, model, emptyList())

        runner.run(session.id, "plan it", Activation("grill-me", "grill text ".repeat(20))).toList()
        runner.run(session.id, "next").toList()

        val compaction = store.entries(agent, session.id).filterIsInstance<Compaction>().last()
        assertEquals(listOf(Activation("grill-me", "grill text ".repeat(20))), compaction.activations)
    }

    @Test
    fun userActivationStaysWithItsTurnWhenCompacting() = runTest {
        val home = TestHome()
        val store = home.store
        val base = home.agent
        val agent = base.copy(config = base.config.copy(compactAtTokens = 10))
        val session = store.createSession(agent, "")
        store.append(agent, session.id, UserMessage("x".repeat(200)))
        val seen = mutableListOf<List<SessionEntry>>()
        val model = Model { _, h, _ -> seen += h; flowOf(StreamFrame.TextDelta("s"), StreamFrame.End()) }

        AgentRunner(agent, store, model, emptyList(), context = "Fresh Workspace").run(session.id, "go", Activation("tdd", "tdd text")).toList()

        assertEquals(listOf(Compaction("s", keepFrom = 2), Context("Fresh Workspace"), Activation("tdd", "tdd text"), UserMessage("go")), seen.last())
    }
}
