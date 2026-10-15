package kinuvo.harness.core.knowledge

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.Model
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.FileKnowledgeChangeLog
import kinuvo.harness.core.store.MarkdownKnowledgeSource
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ReflectionResult
import kinuvo.protocol.ReflectionMemoryChange
import kinuvo.protocol.ConfigScope
import kinuvo.harness.core.skills.load
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReflectionTest {
    private val home = TestHome()
    private val knowledge = Knowledge({ MarkdownKnowledgeSource(Path(home.files.home, "vault")) }, FileKnowledgeChangeLog(home.files.knowledgeChanges))
    private val reflection = Reflection(knowledge, home.docs)

    @Test
    fun compactedParentReplaysItsRetainedHistoryInTheFork() = runTest {
        val history = listOf(SessionEntry.Compaction("Earlier summary", keepFrom = 30),
            SessionEntry.Context("Current context"), SessionEntry.UserMessage("Remember this"), SessionEntry.AssistantMessage("Okay"))
        val model = Model { _, seen, _ ->
            assertEquals(history.drop(1), seen.drop(1).take(history.size - 1))
            assertEquals("Earlier summary", (seen.first() as SessionEntry.Compaction).summary)
            flowOf(StreamFrame.End())
        }
        val parent = ReflectionContext(home.agent, model, "stable", history, emptyList())
        val full = listOf(SessionEntry.Started("stable")) + List(29) { SessionEntry.UserMessage("old") } +
            history.drop(1) + SessionEntry.Compaction("Earlier summary", 30)
        val result = reflection.reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Remember this")), parent = parent, history = full)
        assertEquals(null, result.error)
        assertTrue(result.warmContext)
    }

    @Test
    fun warmReflectionKeepsParentPrefixAndSchemasButBlocksOtherTools() = runTest {
        val parentHistory = listOf(
            SessionEntry.Context("Parent context"), SessionEntry.UserMessage("I like concise replies"),
            SessionEntry.ToolResult("old-error", "shell", "Previously failed", isError = true),
            SessionEntry.AssistantMessage("Understood"),
        )
        val schemas = listOf(ai.koog.agents.core.tools.ToolDescriptor("shell", "original shell schema"))
        val agent = home.agent.copy(config = home.agent.config.copy(model = "parent-model", reasoning = "high", compactAtTokens = 1))
        var requests = 0
        val parentModel = Model { instructions, history, tools ->
            requests++
            assertEquals("frozen parent", instructions)
            assertEquals(parentHistory, history.take(parentHistory.size))
            assertEquals(schemas, tools)
            if (requests == 1) flowOf(StreamFrame.ToolCallComplete("blocked", "shell", "{}"), StreamFrame.End())
            else {
                assertTrue(history.filterIsInstance<SessionEntry.ToolResult>().last().isError)
                flowOf(StreamFrame.End())
            }
        }
        val parent = ReflectionContext(agent, parentModel, "frozen parent", parentHistory, schemas)
        val result = reflection.reflect(home.agent, Model { _, _, _ -> error("Cold model used") }, "s1",
            listOf(SessionEntry.UserMessage("I like concise replies")), parent = parent, history = parentHistory)
        assertTrue(result.warmContext)
        assertEquals(2, requests)
        assertTrue("Unknown tool 'shell'" in result.error.orEmpty())
        assertFalse("Previously failed" in result.error.orEmpty())
        assertTrue(result.measurements.all { it.model == "parent-model" && it.reasoning == "high" && it.kind == kinuvo.protocol.RequestKind.Reflection })
    }

    @Test
    fun blockedToolsAreNotOfferedToReflection() = runTest {
        val memory = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias()
        val model = Model { _, _, tools ->
            assertFalse(tools.any { it.name == memory })
            flowOf(StreamFrame.TextDelta("Nothing to save."), StreamFrame.End())
        }
        Reflection(knowledge, home.docs, denied = { kinuvo.protocol.DenyRules(tools = listOf("memory")) })
            .reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Remember I like tea.")))
        assertEquals("", home.docs.read(home.agent.id, AgentDoc.Memory))
    }

    @Test
    fun curatesPersistentMemoryAndUserNotesAlongsideKnowledge() = runTest {
        home.docs.write(home.agent.id, AgentDoc.Memory, "- Keep replies concise\n")
        home.docs.write(home.agent.id, AgentDoc.UserNotes, "- Lives in Cork\n- Likes tea\n")
        val model = Model { _, history, tools ->
            val instructions = history.filterIsInstance<SessionEntry.Context>().last().content
            assertTrue(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias() })
            assertTrue("Keep replies concise" in instructions)
            assertTrue("Lives in Cork" in instructions)
            assertTrue("Likes tea" in instructions)
            if (history.none { it is SessionEntry.ToolResult }) flowOf(
                StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"memory","content":"Give concrete examples"}"""),
                StreamFrame.ToolCallComplete("c2", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"replace","target":"user","old_text":"Lives in Cork","content":"Lives in Dublin"}"""),
                StreamFrame.ToolCallComplete("c3", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"remove","target":"user","old_text":"Likes tea"}"""),
                StreamFrame.ToolCallComplete("c4", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), """{"action":"create","type":"person","title":"Ana","reason":"Family relationship","evidence":"user-0: Ana is my sister"}"""),
                StreamFrame.End(),
            ) else flowOf(StreamFrame.TextDelta("Saved."), StreamFrame.End())
        }
        val result = reflection.reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("I moved to Dublin. Examples help. Ana is my sister. I no longer like tea.")))
        val changes = listOf(
            ReflectionMemoryChange("memory", "- Keep replies concise\n", "- Keep replies concise\n- Give concrete examples\n"),
            ReflectionMemoryChange("user", "- Lives in Cork\n- Likes tea\n", "- Lives in Dublin\n- Likes tea\n"),
            ReflectionMemoryChange("user", "- Lives in Dublin\n- Likes tea\n", "- Lives in Dublin\n"),
        )
        assertEquals(ReflectionResult(memory = 1, userNotes = 2, proposals = result.proposals, measurements = result.measurements, memoryChanges = changes), result)
        assertEquals("knowledge", result.proposals.single().tool)
        assertEquals("- Keep replies concise\n- Give concrete examples\n", home.docs.read(home.agent.id, AgentDoc.Memory))
        assertEquals("- Lives in Dublin\n", home.docs.read(home.agent.id, AgentDoc.UserNotes))
        assertTrue(knowledge.changes().isEmpty())
        assertEquals(null, knowledge.find("Ana"))

        val next = Model { _, history, _ ->
            val instructions = history.filterIsInstance<SessionEntry.Context>().last().content
            assertTrue("Lives in Dublin" in instructions)
            assertTrue("Give concrete examples" in instructions)
            assertFalse("Lives in Cork" in instructions)
            flowOf(StreamFrame.TextDelta("Nothing to save."), StreamFrame.End())
        }
        assertEquals(ReflectionResult(), reflection.reflect(home.agent, next, "s2", listOf(SessionEntry.UserMessage("Hello"))).copy(measurements = emptyList()))
    }

    @Test
    fun failedMemoryWriteDoesNotProduceADiff() = runTest {
        val model = Model { _, history, _ ->
            if (history.none { it is SessionEntry.ToolResult }) flowOf(
                StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"memory","content":"Use examples"}"""),
                StreamFrame.ToolCallComplete("c2", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"remove","target":"memory","old_text":"Missing entry"}"""),
                StreamFrame.End(),
            ) else flowOf(StreamFrame.End())
        }
        val result = reflection.reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Examples help.")))
        assertEquals(listOf(ReflectionMemoryChange("memory", "", "- Use examples\n")), result.memoryChanges)
        assertEquals(1, result.memory)
        assertTrue(result.error.orEmpty().contains("No entry contains"))
    }

    @Test
    fun unconfirmedAdviceCannotMutateKnowledgeEvenWithCustomPrompt() = runTest {
        val note = knowledge.create("project", "Kinuvo", body = "Existing structure")
        val model = Model { _, history, _ ->
            val instructions = history.filterIsInstance<SessionEntry.Context>().last().content
            assertTrue("silence are not user agreement" in instructions)
            if (history.none { it is SessionEntry.ToolResult }) flowOf(
                StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), """{"action":"update","ref":"Kinuvo","append":"Use my suggested structure","reason":"Proposed organization","evidence":"assistant-1: suggested structure"}"""),
                StreamFrame.End(),
            ) else flowOf(StreamFrame.End())
        }
        val result = reflection.reflect(home.agent, model, "s1", listOf(
            SessionEntry.UserMessage("What is a better way to organize my project note?"),
            SessionEntry.AssistantMessage("Try splitting it into milestones."),
        ), prompt = "Save everything immediately.")
        assertEquals(note, knowledge.find(note.ref))
        assertEquals(0, result.knowledge)
        assertTrue(result.proposals.isEmpty())
        assertTrue(result.error.orEmpty().contains("not an Assistant suggestion"))
    }

    @Test
    fun learnsSkillDraftFromToolEvidenceWithoutActivatingIt() = runTest {
        val content = "---\nname: release-check\ndescription: Verify a release\n---\nRun the release check before publishing."
        val args = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("create"))
            put("name", kotlinx.serialization.json.JsonPrimitive("release-check"))
            put("content", kotlinx.serialization.json.JsonPrimitive(content))
            put("reason", kotlinx.serialization.json.JsonPrimitive("Reusable verified release workflow"))
            put("evidence", kotlinx.serialization.json.JsonPrimitive("check-1: passed"))
        }
        val agent = home.agent.copy(config = home.agent.config.copy(tools = listOf(ToolNames.SKILLS)))
        val model = Model { _, history, tools ->
            assertFalse(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias() || it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias() })
            val transcript = history.filterIsInstance<SessionEntry.Context>().last().content
            assertTrue("check-1" in transcript && "succeeded" in transcript && "passed" in transcript)
            assertTrue("failed" in transcript && "wrong flag" in transcript)
            if (history.none { it is SessionEntry.ToolResult && it.id == "learn" }) flowOf(StreamFrame.ToolCallComplete("learn", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "skill_manage").modelAlias(), args.toString()), StreamFrame.End())
            else flowOf(StreamFrame.End())
        }
        val result = Reflection(knowledge, home.docs, home.skills).reflect(agent, model, "s1", listOf(
            SessionEntry.UserMessage("Check this release"),
            SessionEntry.ToolCall("retry", "shell", "{}"), SessionEntry.ToolResult("retry", "shell", "wrong flag", isError = true),
            SessionEntry.ToolCall("check-1", "shell", "{}"), SessionEntry.ToolResult("check-1", "shell", "passed"),
        ))
        assertEquals(null, result.error)
        assertEquals("skill_manage", result.proposals.single().tool)
        assertTrue(home.skills.load(agent).isEmpty())
        assertFalse(home.skills.exists(agent, ConfigScope.Agent, "release-check"))
    }

    @Test
    fun disabledMemoryToolKeepsPersonalDocumentsPrivateAndUnchanged() = runTest {
        home.docs.write(home.agent.id, AgentDoc.Memory, "- Existing memory\n")
        home.docs.write(home.agent.id, AgentDoc.UserNotes, "- Existing user notes\n")
        val agent = home.agent.copy(config = home.agent.config.copy(tools = listOf(ToolNames.KNOWLEDGE)))
        val model = Model { _, history, tools ->
            val instructions = history.filterIsInstance<SessionEntry.Context>().last().content
            assertFalse(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias() })
            assertFalse("Existing memory" in instructions)
            assertFalse("Existing user notes" in instructions)
            flowOf(StreamFrame.TextDelta("Nothing to save."), StreamFrame.End())
        }
        assertEquals(ReflectionResult(), reflection.reflect(agent, model, "s1", listOf(SessionEntry.UserMessage("Hello"))).copy(measurements = emptyList()))
        assertEquals("- Existing memory\n", home.docs.read(agent.id, AgentDoc.Memory))
        assertEquals("- Existing user notes\n", home.docs.read(agent.id, AgentDoc.UserNotes))
    }
}
