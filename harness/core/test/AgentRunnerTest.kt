package kinuvo.harness.core

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.MemoryTool
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolRun
import kinuvo.protocol.Attachment
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunnerTest {
    @Test
    fun defaultBudgetAllowsThirtyStepsAndReportsExhaustion() = runTest {
        val home = TestHome()
        val agent = home.agent
        val session = home.store.createSession(agent, "")
        var calls = 0
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject) = "OK"
        }
        val model = Model { _, _, _ -> flowOf(StreamFrame.ToolCallComplete("c${++calls}", "read", "{}"), StreamFrame.End()) }
        val events = AgentRunner(agent, home.store, model, listOf(tool)).run(session.id, "go").toList()
        assertEquals(30, calls)
        assertTrue(events.none { it is AgentEvent.RunFinished })
        assertTrue(assertIs<AgentEvent.RunError>(events.last()).message.contains("30 model steps"))
    }

    private val home = TestHome()
    private val store = home.store
    private val agent = home.agent

    @Test
    fun contextChangesAppendWithoutRewritingEarlierRequestPrefixes() = runTest {
        val session = store.createSession(agent, "stable")
        val seen = mutableListOf<List<SessionEntry>>()
        val model = Model { instructions, history, _ ->
            assertEquals("stable", instructions)
            seen += history
            flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        }
        AgentRunner(agent, store, model, emptyList(), context = "Today: Monday").run(session.id, "one").toList()
        AgentRunner(agent, store, model, emptyList(), context = "Today: Monday").run(session.id, "two").toList()
        AgentRunner(agent, store, model, emptyList(), context = "Today: Tuesday").run(session.id, "three").toList()
        assertEquals(seen[0], seen[1].take(seen[0].size))
        assertEquals(seen[1], seen[2].take(seen[1].size))
        assertEquals(listOf("Today: Monday", "Today: Tuesday"), seen[2].filterIsInstance<SessionEntry.Context>().map { it.content })
        assertTrue(seen.flatten().none { it is SessionEntry.Measurement })
        val metrics = store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>()
        assertEquals(3, metrics.size)
        assertTrue(metrics.all { it.metrics.success && it.metrics.cachedTokens == null && it.metrics.firstTokenMs != null })
    }

    @Test
    fun filesToolsAttachAreKeptWithTheirResult() = runTest {
        val session = store.createSession(agent, "")
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("look", "Look")
            override suspend fun execute(args: JsonObject) = error("needs a run")
            override suspend fun execute(args: JsonObject, callId: String, run: ToolRun) = "Attached ${run.attach("photo.png", byteArrayOf(1)).name}"
        }
        var step = 0
        val model = Model { _, _, _ ->
            if (step++ == 0) flowOf(StreamFrame.ToolCallComplete("c1", "look", "{}"), StreamFrame.End()) else flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        }
        val stored = Attachment("a1", "photo.png", "image/png", 1)
        AgentRunner(agent, store, model, listOf(tool), attach = { name, bytes -> assertEquals("photo.png", name); assertEquals(1, bytes.size); stored }).run(session.id, "go").toList()
        val result = store.entries(agent, session.id).filterIsInstance<SessionEntry.ToolResult>().single()
        assertEquals("Attached photo.png", result.output)
        assertEquals(listOf(stored), result.attachments)

        step = 0
        AgentRunner(agent, store, model, listOf(tool)).run(session.id, "again").toList()
        assertTrue(store.entries(agent, session.id).filterIsInstance<SessionEntry.ToolResult>().last().isError)
    }

    @Test
    fun repeatedFailingCallsAreNotRunAgainAndEndTheRun() = runTest {
        var runs = 0
        val failing = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject): String { runs++; error("No such file") }
        }
        var step = 0
        val model = Model { _, _, _ ->
            val args = if (step++ % 2 == 0) """{"path":"a","name":"b"}""" else """{"name":"b","path":"a"}"""
            flowOf(StreamFrame.ToolCallComplete("c$step", "read", args), StreamFrame.End())
        }
        val session = store.createSession(agent, "")
        val events = AgentRunner(agent, store, model, listOf(failing)).run(session.id, "go").toList()
        assertEquals(1, runs)
        assertEquals(3, step)
        assertIs<AgentEvent.RunError>(events.last())
    }

    @Test
    fun executesToolCallsThenAnswers() = runTest {
        val seenHistories = mutableListOf<List<SessionEntry>>()
        val model = Model { instructions, history, _ ->
            assertEquals("frozen", instructions)
            seenHistories += history
            if (history.none { it is SessionEntry.ToolResult }) flowOf(
                StreamFrame.ReasoningDelta(summary = "should remember"),
                StreamFrame.ReasoningComplete("rs1", emptyList(), listOf("should remember"), encrypted = "enc"),
                StreamFrame.ToolCallComplete(
                    "c1", "memory", """{"action":"add","target":"user","content":"Likes tea"}""",
                ),
                StreamFrame.End(),
            ) else flowOf(StreamFrame.TextDelta("Noted"), StreamFrame.TextDelta("!"), StreamFrame.End())
        }
        val session = store.createSession(agent, "frozen")
        val events = AgentRunner(agent, store, model, listOf(MemoryTool(agent, home.docs))).run(session.id, "I like tea").toList()

        assertIs<AgentEvent.RunStarted>(events.first())
        assertIs<AgentEvent.RunFinished>(events.last())
        assertTrue(events.contains(AgentEvent.ToolCallStart("c1", "memory", """{"action":"add","target":"user","content":"Likes tea"}""")))
        assertEquals("- Likes tea\n", home.docs.read(agent.id, AgentDoc.UserNotes))
        assertEquals(2, seenHistories.size)

        val entries = store.entries(agent, session.id).filterNot { it is SessionEntry.Measurement }
        assertEquals("Noted!", (entries.last() as SessionEntry.AssistantMessage).text)
        assertIs<SessionEntry.ToolResult>(entries[entries.size - 2]).also { assertEquals(false, it.isError) }
        val reasoning = SessionEntry.Reasoning("should remember", "rs1", "enc")
        assertEquals(reasoning, entries.filterIsInstance<SessionEntry.Reasoning>().single().copy(messageId = null))
        assertEquals(reasoning, seenHistories[1].filterIsInstance<SessionEntry.Reasoning>().single().copy(messageId = null))
    }

    @Test
    fun unknownToolAndModelFailureAreReported() = runTest {
        var calls = 0
        val model = Model { _, _, _ ->
            calls++
            if (calls == 1) flowOf(StreamFrame.ToolCallComplete("c1", "nope", "{}"), StreamFrame.End())
            else throw IllegalStateException("backend down")
        }
        val session = store.createSession(agent, "")
        val events = AgentRunner(agent, store, model, emptyList()).run(session.id, "hi").toList()

        assertTrue(events.any { it is AgentEvent.ToolCallResult && it.isError })
        assertEquals("backend down", assertIs<AgentEvent.RunError>(events.last()).message)
        assertEquals(listOf(true, false), store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>().map { it.metrics.success })
    }

    @Test
    fun stoppingMidReplyKeepsPartialText() = runTest {
        val model = Model { _, _, _ -> flow { emit(StreamFrame.TextDelta("partial")); awaitCancellation() } }
        val session = store.createSession(agent, "")
        AgentRunner(agent, store, model, emptyList()).run(session.id, "hi").first { it is AgentEvent.TextMessageContent }

        assertEquals(false, store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>().single().metrics.success)
        assertEquals("partial\n\n[interrupted]", (store.entries(agent, session.id).filterIsInstance<SessionEntry.AssistantMessage>().last()).text)
    }

    @Test
    fun stoppingMidToolCallRecordsCancelledResult() = runTest {
        val slowTool = object : Tool {
            override val descriptor = ToolDescriptor("slow", "never finishes")
            override suspend fun execute(args: JsonObject): String = awaitCancellation()
        }
        val model = Model { _, _, _ -> flowOf(StreamFrame.ToolCallComplete("c1", "slow", "{}"), StreamFrame.End()) }
        val session = store.createSession(agent, "")
        AgentRunner(agent, store, model, listOf(slowTool)).run(session.id, "go").first { it is AgentEvent.ToolCallStart }

        assertEquals(SessionEntry.ToolResult("c1", "slow", "Cancelled by user", isError = true), store.entries(agent, session.id).last())
    }
}
