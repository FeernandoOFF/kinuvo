package kinuvo.server

import kinuvo.testing.server.AgentRun
import kinuvo.server.plugin.toolSpec
import ai.koog.prompt.message.Message
import kinuvo.protocol.model.ModelTier
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.testing.harness.*
import kinuvo.server.plugin.Tool
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.usage.RequestKind
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionEntry.*
import kinuvo.protocol.session.ToolCallId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kinuvo.harness.builtin.activeHistory
import kinuvo.harness.builtin.recentScreens
import kinuvo.harness.ModelProfile
import kinuvo.harness.ResultLimits
import kinuvo.harness.SCREENSHOT_PREFIX

class CompactionTest {
    @Test
    fun onlyTheLatestScreenshotsAreSentAgain() {
        fun shot(n: Int) = ToolResult(ToolCallId("$n"), "computer_capture", "screen $n", attachments = listOf(kinuvo.protocol.session.Attachment("$n", "${SCREENSHOT_PREFIX}$n.jpg", "image/jpeg", 10)))
        val file = ToolResult(ToolCallId("f"), "knowledge", "note", attachments = listOf(kinuvo.protocol.session.Attachment("f", "plan.pdf", "application/pdf", 10)))
        val pruned = (listOf(file) + (1..5).map(::shot)).recentScreens(keep = 3).filterIsInstance<ToolResult>()
        assertEquals(listOf(1, 0, 0, 1, 1, 1), pruned.map { it.attachments.size })
        assertTrue(pruned[1].output.endsWith("no longer shown.)"))
    }

    @Test
    fun activeHistoryStartsAtTheLatestSnapshotAndExcludesReflection() {
        val snapshot = listOf(Context("Current context"), UserMessage("current"), AssistantMessage("summary"))
        val all = listOf(Started("i"), UserMessage("old"), Compaction("old summary", emptyList()),
            Compaction("summary", snapshot), SessionEntry.Reflection("r", 123, kinuvo.protocol.reflection.ReflectionResult()), AssistantMessage("reply"))
        assertEquals(snapshot + AssistantMessage("reply"), all.activeHistory())
        assertEquals(listOf(UserMessage("old")), all.take(2).activeHistory())
    }

    @Test
    fun theSoftThresholdAllowsAFreshRequestThatFitsTheModel() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 1))
        val session = home.store.createSession(agent.id, "")
        var requests = 0
        val model = scriptedModel { _, _ -> requests++; flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) }
        val events = AgentRun(agent, home.store, model, emptyList(), contextTokens = 1024).run(session.id, "hello").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(1, requests)
    }

    @Test
    fun shortTwoTurnHistoryAboveTheSoftThresholdIsNotSummarized() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 2252))
        val session = home.store.createSession(agent.id, "i".repeat(9200))
        var calls = 0
        val model = scriptedModel { _, _ -> calls++; flowOf(StreamFrame.TextDelta("I can use Browser tools."), StreamFrame.End()) }
        repeat(2) { index ->
            val events = AgentRun(agent, home.store, model, emptyList(), contextTokens = 4096)
                .run(session.id, if (index == 0) "What tools do you have access to?" else "Can you open the browser?").toList()
            assertIs<AgentEvent.RunFinished>(events.last())
            assertTrue(events.none { it is AgentEvent.Compacted })
        }
        assertEquals(2, calls)
    }

    @Test
    fun providerTokenCountEnforcesTheHardBudgetAndExplainsTheOverhead() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "short")
        var calls = 0
        val model = scriptedModel { _, _ -> calls++; flowOf(StreamFrame.End()) }
            .copy(estimateTokens = { _, _ -> 3500 })
        val events = AgentRun(home.agent, home.store, model, emptyList(), contextTokens = 4096).run(session.id, "hello").toList()
        val error = assertIs<AgentEvent.RunError>(events.last())
        assertContains(error.message, "Input 3500 tokens; budget 3072")
        assertEquals(0, calls)
    }

    @Test
    fun oversizedFreshRequestIsRejectedBeforeCallingTheModel() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
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
        val session = home.store.createSession(agent.id, "")
        home.store.append(agent.id, session.id, UserMessage("x".repeat(2400)))
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("y".repeat(4000)), StreamFrame.End()) }
        val events = AgentRun(agent, home.store, model, emptyList()).run(session.id, "next").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertTrue(home.store.entries(agent.id, session.id).none { it is Compaction })
    }

    @Test
    fun builtinCompressionPersistsAndReloadsTheExactHistory() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 400))
        val session = home.store.createSession(agent.id, "stable")
        home.store.append(agent.id, session.id, UserMessage("Earlier question"))
        home.store.append(agent.id, session.id, AssistantMessage("x".repeat(8000)))
        home.store.append(agent.id, session.id, ToolCall(ToolCallId("activate"), "activate_skill", """{"name":"loaded"}""",
            kinuvo.protocol.plugin.ToolIdentity(kinuvo.protocol.plugin.PluginInstanceId("kinuvo.core"), "activate_skill")))
        home.store.append(agent.id, session.id, ToolResult(ToolCallId("activate"), "activate_skill", "Loaded Skill instructions"))
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
        val all = home.store.entries(agent.id, session.id)
        assertEquals(setOf("skill", "loaded"), all.filterIsInstance<Compaction>().single().history.filterIsInstance<Activation>().map { it.name }.toSet())
        assertTrue(all.any { it is AssistantMessage && it.text.length == 8000 })
        assertEquals(listOf(RequestKind.Compaction, RequestKind.Chat), all.filterIsInstance<Measurement>().map { it.metrics.kind })
        AgentRun(agent, home.store, model, emptyList(), context = "Current context").run(session.id, "again").toList()
        assertEquals(seen.first(), seen.last().take(seen.first().size))
    }

    @Test
    fun dailyContextsDoNotAccumulateAcrossCompactions() = runTest {
        val home = TestHome { 1234L }
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 400))
        val session = home.store.createSession(agent.id, "stable")
        repeat(20) { home.store.append(agent.id, session.id, Context("Day $it: " + "x".repeat(1000))) }
        home.store.append(agent.id, session.id, UserMessage("earlier"))
        home.store.append(agent.id, session.id, AssistantMessage("y".repeat(8000)))
        val model = scriptedModel { prompt, _ ->
            if (prompt.messages.last().textContent().contains("Create a comprehensive summary"))
                flowOf(StreamFrame.TextDelta("Earlier work."), StreamFrame.End())
            else {
                assertEquals(listOf("stable", "Today"), prompt.messages.filterIsInstance<Message.System>().map { it.textContent() })
                flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
            }
        }
        val events = AgentRun(agent, home.store, model, emptyList(), context = "Today", contextTokens = 4096).run(session.id, "next").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(listOf(Context("Today", writtenAt = 1234L)), home.store.entries(agent.id, session.id).filterIsInstance<Compaction>().single().history.filterIsInstance<Context>())
    }

    @Test
    fun aSingleOversizedEarlierToolResultIsSummarizedInBoundedRequests() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "stable")
        home.store.append(home.agent.id, session.id, UserMessage("read"))
        home.store.append(home.agent.id, session.id, ToolCall(ToolCallId("old"), "read", "{}"))
        home.store.append(home.agent.id, session.id, ToolResult(ToolCallId("old"), "read", "x".repeat(40000) + "TAIL"))
        var summaries = 0
        var tail = false
        val model = scriptedModel { prompt, _ ->
            assertTrue(prompt.messages.sumOf { it.textContent().length } / 4 < 3072)
            if (prompt.messages.last().textContent().contains("Create a comprehensive summary")) {
                summaries++
                tail = tail || prompt.messages.any { "TAIL" in it.textContent() }
                flowOf(StreamFrame.TextDelta("Read completed."), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        }
        val events = AgentRun(home.agent, home.store, model, emptyList(), contextTokens = 4096,
            profile = ModelProfile.of(ModelTier.Frontier).copy(resultLimits = ResultLimits(100_000, null))).run(session.id, "next").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertTrue(summaries > 1)
        assertTrue(tail)
    }

    @Test
    fun compressesToolExchangesAndPreservesFilesAndTheCurrentRequest() = runTest {
        val home = TestHome()
        val agent = home.agent.copy(config = home.agent.config.copy(compactAtTokens = 2000))
        val session = home.store.createSession(agent.id, "")
        val photo = Attachment("photo", "photo.png", "image/png", 1)
        val tool = object : Tool {
            override val descriptor = toolSpec("read", "Read")
            override suspend fun execute(args: JsonObject) = "x".repeat(20000)
            override suspend fun execute(args: JsonObject, callId: String, run: kinuvo.server.plugin.ToolRun): String {
                run.attach("photo.png", byteArrayOf(1))
                return execute(args)
            }
        }
        var chat = 0
        val model = scriptedModel { prompt, tools ->
            if (tools.isEmpty()) flowOf(StreamFrame.TextDelta("Read the photo."), StreamFrame.End())
            else if (chat++ < 2) flowOf(StreamFrame.ToolCallComplete("call-$chat", "read", "{}"), StreamFrame.End())
            else {
                val userAt = prompt.messages.indexOfFirst { it.textContent() == "Original request" }
                val callAt = prompt.messages.indexOfFirst { it.parts.any { p -> p is MessagePart.Tool.Call && p.id == "call-2" } }
                val resultAt = prompt.messages.indexOfFirst { it.parts.any { p -> p is MessagePart.Tool.Result && p.id == "call-2" } }
                assertTrue(userAt > 0)
                assertTrue(callAt > userAt && resultAt > callAt)
                assertTrue(prompt.messages.take(userAt).any { it is Message.Assistant })
                assertTrue(prompt.messages.flatMap { it.parts }.any { it is MessagePart.Attachment })
                flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
            }
        }
        val events = AgentRun(agent, home.store, model, listOf(tool), attach = { _, _ -> photo }).run(session.id, "Original request").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        val snapshot = home.store.entries(agent.id, session.id).filterIsInstance<Compaction>().single().history
        assertEquals(listOf("call-2"), snapshot.filterIsInstance<ToolCall>().map { it.id.value })
        assertEquals("x".repeat(20000), snapshot.filterIsInstance<ToolResult>().single().output)
    }
}
