package kinuvo.harness.core

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.Tool
import kotlinx.serialization.json.JsonObject
import kinuvo.harness.core.tools.WebResult
import kinuvo.harness.core.tools.WebSearch
import kinuvo.harness.core.tools.WebSearchTool
import kinuvo.harness.core.tools.MemoryTool
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ModelTier
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class SmallModelAnswerTest {
    @Test fun nudgeAppliesOnlyToSmallTiers() = runTest {
        for (tier in listOf(ModelTier.Micro, ModelTier.Small, ModelTier.Mid, ModelTier.Frontier)) {
            val home = TestHome()
            val session = home.store.createSession(home.agent.id, "")
            var requests = 0
            val tool = object : Tool {
                override val descriptor = ToolDescriptor("read", "Read")
                override suspend fun execute(args: JsonObject) = "result"
            }
            val model = scriptedModel { prompt, _ ->
                if (requests++ == 0) flowOf(StreamFrame.ToolCallComplete("call", "read", "{}"), StreamFrame.End())
                else {
                    assertEquals(tier in listOf(ModelTier.Micro, ModelTier.Small), prompt.messages.any { it.textContent().contains(ANSWER_NUDGE) })
                    flowOf(StreamFrame.TextDelta("Answer"), StreamFrame.End())
                }
            }
            val events = AgentRun(home.agent, home.store, model, listOf(tool), profile = ModelProfile.of(tier)).run(session.id, "go").toList()
            assertIs<AgentEvent.RunFinished>(events.last())
            assertTrue(home.store.entries(home.agent.id, session.id).none { it.toString().contains(ANSWER_NUDGE) })
        }
    }

    @Test fun microBlocksRewordedSearchAndAnswers() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        var searches = 0
        var requests = 0
        val tool = WebSearchTool(object : WebSearch {
            override suspend fun search(query: String, limit: Int, news: Boolean): List<WebResult> {
                searches++
                assertEquals(3, limit)
                return listOf(WebResult("Ada", "https://example.org/ada", "A mathematician."))
            }
        })
        val queries = listOf("Who is Ada Lovelace?", "Ada Lovelace", "Lovelace published notes 1843")
        val model = scriptedModel { prompt, tools ->
            if (requests > 0) assertTrue(prompt.messages.any { it.textContent().contains(ANSWER_NUDGE) || it.textContent().contains(ANSWER_NOW) })
            if (tools.isEmpty()) flowOf(StreamFrame.TextDelta("Ada was a mathematician."), StreamFrame.End())
            else flowOf(StreamFrame.ToolCallComplete("c${requests}", "web_search",
                """{"query":"${queries[requests++]}"}"""), StreamFrame.End())
        }
        val events = AgentRun(home.agent, home.store, model, listOf(tool), contextTokens = 4096,
            profile = ModelProfile.of(ModelTier.Micro)).run(session.id, "Who is Ada Lovelace?").toList()
        assertEquals(1, searches)
        assertEquals("Ada was a mathematician.", events.filterIsInstance<AgentEvent.TextMessageEnd>().last().text)
        assertTrue(events.none { it is AgentEvent.Compacted })
        assertTrue(events.filterIsInstance<AgentEvent.ToolCallResult>().any { it.content == SEARCHED })
        assertTrue(home.store.entries(home.agent.id, session.id).withoutBlockedRepeats().none { it is SessionEntry.ToolResult && it.output == SEARCHED })
        assertIs<AgentEvent.RunFinished>(events.last())
    }

    @Test fun compactSearchLeavesFullSearchUnchanged() = runTest {
        val tool = WebSearchTool(object : WebSearch {
            override suspend fun search(query: String, limit: Int, news: Boolean) =
                listOf(WebResult("Title", "https://example.org/path?q=long", "x".repeat(199) + "😀end", "2026-01-01"))
        })
        val args = buildJsonObject { put("query", "query") }
        val full = tool.execute(args)
        val compact = tool.compact().execute(args)
        assertTrue(full.contains("2026-01-01"))
        assertTrue(full.contains("😀end"))
        assertFalse(compact.contains("2026-01-01"))
        assertFalse(compact.contains("https://"))
        assertFalse(compact.contains("?q="))
        assertFalse(compact.any { it.isSurrogate() })
    }

    @Test fun microSeesEarlierQuestionsButNotReplies() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        val prompts = mutableListOf<String>()
        val model = scriptedModel { prompt, _ ->
            prompts += prompt.messages.joinToString("\n") { it.textContent() }
            flowOf(StreamFrame.TextDelta("Reply ${prompts.size}."), StreamFrame.End())
        }
        val profile = ModelProfile.of(ModelTier.Micro)
        for (text in listOf("Who is Ada Lovelace?", "Where was she born?", "And her father?"))
            AgentRun(home.agent, home.store, model, emptyList(), contextTokens = 4096, profile = profile).run(session.id, text).toList()
        assertFalse("Earlier in this conversation" in prompts[0])
        assertTrue("- Who is Ada Lovelace?" in prompts[1])
        assertTrue("- Who is Ada Lovelace?" in prompts[2] && "- Where was she born?" in prompts[2])
        assertFalse(prompts[2].contains("Reply 1.") || prompts[2].contains("Reply 2."))
    }

    @Test fun microSavesMemoryOnlyWhenAsked() = runTest {
        for ((text, saves) in listOf("When is Mexican Independence Day?" to 0, "Remember that I like tea" to 1)) {
            val home = TestHome()
            val session = home.store.createSession(home.agent.id, "")
            var requests = 0
            val model = scriptedModel { _, tools ->
                if (tools.isEmpty() || requests++ > 0) flowOf(StreamFrame.TextDelta("September 16."), StreamFrame.End())
                else flowOf(StreamFrame.ToolCallComplete("m", "memory", """{"action":"add","target":"user","content":"Likes tea"}"""), StreamFrame.End())
            }
            val events = AgentRun(home.agent, home.store, model, listOf(MemoryTool(home.agent, home.docs)), contextTokens = 4096,
                profile = ModelProfile.of(ModelTier.Micro)).run(session.id, text).toList()
            assertEquals(saves, home.docs.read(home.agent.id, AgentDoc.UserNotes).lines().count { it.isNotBlank() })
            assertIs<AgentEvent.RunFinished>(events.last())
            assertEquals("September 16.", events.filterIsInstance<AgentEvent.TextMessageEnd>().last().text)
        }
    }
}
