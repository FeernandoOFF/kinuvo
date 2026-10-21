package kinuvo.harness.core

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RequestKind
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

class AgentRunTest {
    @Test
    fun koogIterationLimitStopsChatAndReflectionAndClosesPendingCalls() = runTest {
        for (kind in listOf(RequestKind.Chat, RequestKind.Reflection)) {
            for (batch in listOf(1, 3)) {
                val home = TestHome()
                val session = home.store.createSession(home.agent, "")
                var calls = 0
                var executed = 0
                val tool = object : Tool {
                    override val descriptor = ToolDescriptor("read", "Read")
                    override suspend fun execute(args: JsonObject): String { executed++; return "OK" }
                }
                val model = scriptedModel { _, _ ->
                    calls++
                    flow {
                        repeat(batch) { emit(StreamFrame.ToolCallComplete("c$calls-$it", "read", "{}")) }
                        emit(StreamFrame.End())
                    }
                }
                val events = AgentRun(home.agent, home.store, model, listOf(tool), requestKind = kind).run(session.id, "go").toList()
                // The start node, history checks, model requests and each tool call share 50 iterations.
                assertEquals(if (batch == 1) 16 else 10, calls)
                assertEquals(if (batch == 1) 16 else 29, executed)
                assertTrue(events.none { it is AgentEvent.RunFinished })
                assertEquals("The work is unfinished; send another message to continue.", assertIs<AgentEvent.RunError>(events.last()).message)
                val results = events.filterIsInstance<AgentEvent.ToolCallResult>()
                assertEquals(calls * batch, results.size)
                assertEquals(if (batch == 1) 0 else 1, results.count { it.isError })
                val entries = home.store.entries(home.agent, session.id)
                assertEquals(entries.filterIsInstance<SessionEntry.ToolCall>().map { it.id }, entries.filterIsInstance<SessionEntry.ToolResult>().map { it.id })
            }
        }
    }

    private val home = TestHome()
    private val store = home.store
    private val agent = home.agent

    @Test
    fun onlyTheLatestContextIsSentWhileTheTranscriptKeepsEarlierContexts() = runTest {
        val session = store.createSession(agent, "stable")
        val seen = mutableListOf<List<Message>>()
        val model = scriptedModel { prompt, _ ->
            val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
            val history = prompt.messages
            assertEquals("stable", instructions)
            seen += history
            flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        }
        AgentRun(agent, store, model, emptyList(), context = "Today: Monday").run(session.id, "one").toList()
        AgentRun(agent, store, model, emptyList(), context = "Today: Monday").run(session.id, "two").toList()
        AgentRun(agent, store, model, emptyList(), context = "Today: Tuesday").run(session.id, "three").toList()
        assertEquals(seen[0], seen[1].take(seen[0].size))
        assertEquals(listOf("Today: Tuesday"), seen[2].filterIsInstance<Message.System>().drop(1).map { it.textContent() })
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
        val model = scriptedModel { _, _ ->
            if (step++ == 0) flowOf(StreamFrame.ToolCallComplete("c1", "look", "{}"), StreamFrame.End()) else flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        }
        val stored = Attachment("a1", "photo.png", "image/png", 1)
        AgentRun(agent, store, model, listOf(tool), attach = { name, bytes -> assertEquals("photo.png", name); assertEquals(1, bytes.size); stored }).run(session.id, "go").toList()
        val result = store.entries(agent, session.id).filterIsInstance<SessionEntry.ToolResult>().single()
        assertEquals("Attached photo.png", result.output)
        assertEquals(listOf(stored), result.attachments)

        step = 0
        AgentRun(agent, store, model, listOf(tool)).run(session.id, "again").toList()
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
        val model = scriptedModel { _, _ ->
            val args = if (step++ % 2 == 0) """{"path":"a","name":"b"}""" else """{"name":"b","path":"a"}"""
            flowOf(StreamFrame.ToolCallComplete("c$step", "read", args), StreamFrame.End())
        }
        val session = store.createSession(agent, "")
        val events = AgentRun(agent, store, model, listOf(failing)).run(session.id, "go").toList()
        assertEquals(1, runs)
        assertEquals(3, step)
        assertIs<AgentEvent.RunError>(events.last())
    }

    @Test
    fun executesToolCallsThenAnswers() = runTest {
        val seenHistories = mutableListOf<List<Message>>()
        val model = scriptedModel { prompt, _ ->
            val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
            val history = prompt.messages
            assertEquals("frozen", instructions)
            seenHistories += history
            if (history.toolResults.isEmpty()) flowOf(
                StreamFrame.ReasoningDelta(summary = "should remember"),
                StreamFrame.ReasoningComplete("rs1", emptyList(), listOf("should remember"), encrypted = "enc"),
                StreamFrame.ToolCallComplete(
                    "c1", "memory", """{"action":"add","target":"user","content":"Likes tea"}""",
                ),
                StreamFrame.End(),
            ) else flowOf(StreamFrame.TextDelta("Noted"), StreamFrame.TextDelta("!"), StreamFrame.End())
        }
        val session = store.createSession(agent, "frozen")
        val events = AgentRun(agent, store, model, listOf(MemoryTool(agent, home.docs))).run(session.id, "I like tea").toList()

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
        assertEquals("enc", seenHistories[1].flatMap { it.parts }.filterIsInstance<MessagePart.Reasoning>().single().encrypted)
    }

    @Test
    fun unknownToolAndModelFailureAreReported() = runTest {
        var calls = 0
        val model = scriptedModel { _, _ ->
            calls++
            if (calls == 1) flowOf(StreamFrame.ToolCallComplete("c1", "nope", "{}"), StreamFrame.End())
            else throw IllegalStateException("backend down")
        }
        val session = store.createSession(agent, "")
        val events = AgentRun(agent, store, model, emptyList()).run(session.id, "hi").toList()

        assertTrue(events.any { it is AgentEvent.ToolCallResult && it.isError })
        assertEquals("backend down", assertIs<AgentEvent.RunError>(events.last()).message)
        assertEquals(listOf(true, false), store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>().map { it.metrics.success })
    }

    @Test
    fun stoppingMidReplyKeepsPartialText() = runTest {
        val model = scriptedModel { _, _ -> flow { emit(StreamFrame.TextDelta("partial")); awaitCancellation() } }
        val session = store.createSession(agent, "")
        AgentRun(agent, store, model, emptyList()).run(session.id, "hi").first { it is AgentEvent.TextMessageContent }

        assertEquals(false, store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>().single().metrics.success)
        assertEquals("partial\n\n[interrupted]", (store.entries(agent, session.id).filterIsInstance<SessionEntry.AssistantMessage>().last()).text)
    }

    @Test
    fun stoppingMidToolCallRecordsCancelledResult() = runTest {
        val slowTool = object : Tool {
            override val descriptor = ToolDescriptor("slow", "never finishes")
            override suspend fun execute(args: JsonObject): String = awaitCancellation()
        }
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.ToolCallComplete("c1", "slow", "{}"), StreamFrame.End()) }
        val session = store.createSession(agent, "")
        AgentRun(agent, store, model, listOf(slowTool)).run(session.id, "go").first { it is AgentEvent.ToolCallStart }

        assertEquals(SessionEntry.ToolResult("c1", "slow", "Cancelled by user", isError = true), store.entries(agent, session.id).last())
    }
}
