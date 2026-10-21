package kinuvo.harness.core

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.testing.*
import kinuvo.harness.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.Attachment
import kinuvo.protocol.RequestKind
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionEntry.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

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
    fun activeHistoryStartsAtTheLatestSnapshotAndExcludesReflection() {
        val snapshot = listOf(Context("Current context"), UserMessage("current"), AssistantMessage("summary"))
        val all = listOf(Started("i"), UserMessage("old"), Compaction("old summary", emptyList()),
            Compaction("summary", snapshot), SessionEntry.Reflection("r", 123, kinuvo.protocol.ReflectionResult()), AssistantMessage("reply"))
        assertEquals(snapshot + AssistantMessage("reply"), all.activeHistory())
        assertEquals(listOf(UserMessage("old")), all.take(2).activeHistory())
    }

    @Test
    fun theSoftThresholdAllowsAFreshRequestThatFitsTheModel() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 1))
        val session = home.store.createSession(agent, "")
        var requests = 0
        val model = scriptedModel { _, _ -> requests++; flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) }
        val events = AgentRun(agent, home.store, model, emptyList(), contextTokens = 1024).run(session.id, "hello").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(1, requests)
    }

    @Test
    fun oversizedFreshRequestIsRejectedBeforeCallingTheModel() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "")
        var calls = 0
        val model = scriptedModel { _, _ -> calls++; flowOf(StreamFrame.End()) }
        val events = AgentRun(home.agent, home.store, model, emptyList(), contextTokens = 1024).run(session.id, "x".repeat(5000)).toList()
        assertEquals(0, calls)
        assertTrue(events.last() is AgentEvent.RunError)
    }

    @Test
    fun nonReducingSummaryDoesNotReplaceTheActiveHistory() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 50))
        val session = home.store.createSession(agent, "")
        home.store.append(agent, session.id, UserMessage("x".repeat(400)))
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("y".repeat(1000)), StreamFrame.End()) }
        val events = AgentRun(agent, home.store, model, emptyList()).run(session.id, "next").toList()
        assertTrue(events.last() is AgentEvent.RunError)
        assertTrue(home.store.entries(agent, session.id).none { it is Compaction })
    }

    @Test
    fun builtinCompressionPersistsAndReloadsTheExactHistory() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 400))
        val session = home.store.createSession(agent, "stable")
        home.store.append(agent, session.id, UserMessage("Earlier question"))
        home.store.append(agent, session.id, AssistantMessage("x".repeat(8000)))
        home.store.append(agent, session.id, ToolCall("activate", "activate_skill", """{"name":"loaded"}""",
            kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "activate_skill")))
        home.store.append(agent, session.id, ToolResult("activate", "activate_skill", "Loaded Skill instructions"))
        val seen = mutableListOf<List<Message>>()
        val model = scriptedModel { prompt, _ ->
            val summarizing = prompt.messages.last().textContent().contains("Create a comprehensive summary")
            if (summarizing) flowOf(StreamFrame.TextDelta("Earlier answer summarized."), StreamFrame.End())
            else { seen += prompt.messages; flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) }
        }
        val events = AgentRun(agent, home.store, model, emptyList(), context = "Current context")
            .run(session.id, "next", Activation("skill", "Skill instructions")).toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        val all = home.store.entries(agent, session.id)
        assertEquals(setOf("skill", "loaded"), all.filterIsInstance<Compaction>().single().history.filterIsInstance<Activation>().map { it.name }.toSet())
        assertTrue(all.any { it is AssistantMessage && it.text.length == 8000 })
        assertEquals(listOf(RequestKind.Compaction, RequestKind.Chat), all.filterIsInstance<Measurement>().map { it.metrics.kind })
        AgentRun(agent, home.store, model, emptyList(), context = "Current context").run(session.id, "again").toList()
        assertEquals(seen.first(), seen.last().take(seen.first().size))
    }

    @Test
    fun compressesToolExchangesAndPreservesFilesAndTheCurrentRequest() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 2000))
        val session = home.store.createSession(agent, "")
        val photo = Attachment("photo", "photo.png", "image/png", 1)
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject) = "x".repeat(20000)
            override suspend fun execute(args: JsonObject, callId: String, run: kinuvo.harness.core.tools.ToolRun): String {
                run.attach("photo.png", byteArrayOf(1))
                return execute(args)
            }
        }
        var chat = 0
        val model = scriptedModel { prompt, tools ->
            if (tools.isEmpty()) flowOf(StreamFrame.TextDelta("Read the photo."), StreamFrame.End())
            else if (chat++ == 0) flowOf(StreamFrame.ToolCallComplete("real-call-id", "read", "{}"), StreamFrame.End())
            else {
                assertTrue(prompt.messages.any { it.textContent() == "Original request" })
                assertTrue(prompt.messages.flatMap { it.parts }.any { it is MessagePart.Attachment })
                flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
            }
        }
        val events = AgentRun(agent, home.store, model, listOf(tool), attach = { _, _ -> photo }).run(session.id, "Original request").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        assertEquals("real-call-id", home.store.entries(agent, session.id).filterIsInstance<ToolResult>().single().id)
    }
}
