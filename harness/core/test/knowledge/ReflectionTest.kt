package kinuvo.harness.core.knowledge

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.FileKnowledgeChangeLog
import kinuvo.harness.core.store.MarkdownKnowledgeSource
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ReflectionResult
import kinuvo.protocol.ReflectionMemoryChange
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ToolIdentity
import kinuvo.protocol.RequestKind
import kotlinx.coroutines.flow.flow
import ai.koog.prompt.message.ResponseMetaInfo
import kinuvo.harness.core.TokenUsageCapture
import kotlinx.coroutines.currentCoroutineContext
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
    fun compactedParentReplaysItsRetainedHistoryInEachStage() = runTest {
        val history = listOf(SessionEntry.AssistantMessage("Earlier summary"), SessionEntry.Context("Current context"),
            SessionEntry.UserMessage("Remember this"), SessionEntry.AssistantMessage("Okay"))
        val model = scriptedModel { prompt, _ ->
            val seen = prompt.messages
            assertEquals(listOf("Current context", "Remember this", "Okay"), seen.drop(2).take(3).map { it.textContent() })
            assertTrue(seen[1].textContent().contains("Earlier summary"))
            flowOf(StreamFrame.End())
        }
        val parent = ReflectionContext(home.agent, model, "stable", history, emptyList())
        val full = listOf(SessionEntry.Started("stable"), SessionEntry.UserMessage("old"), SessionEntry.Compaction("Earlier summary", history))
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
        val parentModel = scriptedModel { prompt, tools ->
            val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
            val history = prompt.messages
            requests++
            assertEquals("frozen parent", instructions)
            assertEquals(listOf("Parent context", "I like concise replies", "", "Understood"), history.drop(1).take(4).map { it.textContent() })
            assertEquals(schemas, tools)
            if (requests == 1) flowOf(StreamFrame.ToolCallComplete("blocked", "shell", "{}"), StreamFrame.End())
            else {
                assertTrue(history.toolResults.last().isError)
                flowOf(StreamFrame.End())
            }
        }
        val parent = ReflectionContext(agent, parentModel, "frozen parent", parentHistory, schemas)
        val result = reflection.reflect(home.agent, scriptedModel { _, _ -> error("Cold model used") }, "s1",
            listOf(SessionEntry.UserMessage("I like concise replies")), parent = parent, history = parentHistory)
        assertTrue(result.warmContext)
        assertEquals(3, requests)
        assertTrue("Unknown tool 'shell'" in result.error.orEmpty())
        assertFalse("Previously failed" in result.error.orEmpty())
        assertTrue(result.measurements.all { it.model == "parent-model" && it.reasoning == "high" && it.kind == kinuvo.protocol.RequestKind.Reflection })
    }

    @Test
    fun blockedToolsAreNotOfferedToReflection() = runTest {
        val memory = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias()
        val model = scriptedModel { _, tools ->
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
        val model = scriptedModel { prompt, tools ->
            val history = prompt.messages
            val instructions = history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent()
            val personal = tools.single().name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias()
            if (personal) {
                assertTrue("Keep replies concise" in instructions)
                assertTrue("Lives in Cork" in instructions)
                assertTrue("Likes tea" in instructions)
            }
            if (history.toolResults.isNotEmpty()) flowOf(StreamFrame.End())
            else if (personal) flowOf(
                StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"memory","content":"Give concrete examples"}"""),
                StreamFrame.ToolCallComplete("c2", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"replace","target":"user","old_text":"Lives in Cork","content":"Lives in Dublin"}"""),
                StreamFrame.ToolCallComplete("c3", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"remove","target":"user","old_text":"Likes tea"}"""),
                StreamFrame.End(),
            ) else flowOf(
                StreamFrame.ToolCallComplete("c4", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), """{"action":"create","type":"person","title":"Ana","reason":"Family relationship","evidence":"user-0: Ana is my sister"}"""),
                StreamFrame.End(),
            )
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

        val next = scriptedModel { prompt, tools ->
            val history = prompt.messages
            val instructions = history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent()
            if (tools.single().name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias()) {
                assertTrue("Lives in Dublin" in instructions)
                assertTrue("Give concrete examples" in instructions)
                assertFalse("Lives in Cork" in instructions)
            }
            flowOf(StreamFrame.TextDelta("Nothing to save."), StreamFrame.End())
        }
        assertEquals(ReflectionResult(), reflection.reflect(home.agent, next, "s2", listOf(SessionEntry.UserMessage("Hello"))).copy(measurements = emptyList()))
    }

    @Test
    fun failedMemoryWriteDoesNotProduceADiff() = runTest {
        val model = scriptedModel { prompt, tools ->
            val history = prompt.messages
            if (tools.single().name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias() && history.toolResults.isEmpty()) flowOf(
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
        val model = scriptedModel { prompt, tools ->
            val history = prompt.messages
            val instructions = history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent()
            assertTrue("silence are not user agreement" in instructions)
            if (tools.single().name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias() && history.toolResults.isEmpty()) flowOf(
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
        val model = scriptedModel { prompt, tools ->
            val history = prompt.messages
            assertFalse(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias() || it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias() })
            val transcript = history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent()
            assertTrue("check-1" in transcript && "succeeded" in transcript && "passed" in transcript)
            assertTrue("failed" in transcript && "wrong flag" in transcript)
            if (history.toolResults.none { it.id == "learn" }) flowOf(StreamFrame.ToolCallComplete("learn", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "skill_manage").modelAlias(), args.toString()), StreamFrame.End())
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
        val model = scriptedModel { prompt, tools ->
            val history = prompt.messages
            val instructions = history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent()
            assertFalse(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias() })
            assertFalse("Existing memory" in instructions)
            assertFalse("Existing user notes" in instructions)
            flowOf(StreamFrame.TextDelta("Nothing to save."), StreamFrame.End())
        }
        assertEquals(ReflectionResult(), reflection.reflect(agent, model, "s1", listOf(SessionEntry.UserMessage("Hello"))).copy(measurements = emptyList()))
        assertEquals("- Existing memory\n", home.docs.read(agent.id, AgentDoc.Memory))
        assertEquals("- Existing user notes\n", home.docs.read(agent.id, AgentDoc.UserNotes))
    }

    @Test
    fun enabledStagesRunInOrderWithOnlyTheirOwnToolsAndContext() = runTest {
        home.docs.write(home.agent.id, AgentDoc.Memory, "personal-only")
        val features = listOf(ToolNames.MEMORY, ToolNames.KNOWLEDGE, ToolNames.SKILLS)
        val aliases = listOf(
            setOf(ToolIdentity(PluginInstanceId("kinuvo.core"), "memory").modelAlias()),
            setOf(ToolIdentity(PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias()),
            setOf("activate_skill", "read_skill_file", "skill_manage").map { ToolIdentity(PluginInstanceId("kinuvo.core"), it).modelAlias() }.toSet(),
        )
        for (mask in 0..7) {
            val enabled = features.indices.filter { mask and (1 shl it) != 0 }
            val seen = mutableListOf<Int>()
            val model = scriptedModel { prompt, tools ->
                val stage = aliases.indexOf(tools.map { it.name }.toSet())
                assertTrue(stage in enabled)
                seen += stage
                val review = prompt.messages.filterIsInstance<Message.User>().last().textContent()
                assertEquals(stage == 0, "personal-only" in review)
                assertEquals(stage == 1, "How the user organizes it:" in review)
                assertEquals(stage == 2, "Available Skills:" in review)
                flowOf(StreamFrame.End())
            }
            val agent = home.agent.copy(config = home.agent.config.copy(tools = enabled.map { features[it] }))
            val result = Reflection(knowledge, home.docs, home.skills).reflect(agent, model, "s1", listOf(SessionEntry.UserMessage("Review this")))
            assertEquals(null, result.error)
            assertEquals(enabled, seen)
            assertEquals(enabled.size, result.measurements.size)
        }
    }

    @Test
    fun countsSuccessfulEditsDirectlyEvenWhenCallIdsRepeatAndContentDoesNotChange() = runTest {
        home.docs.write(home.agent.id, AgentDoc.Memory, "- Use examples\n")
        val model = scriptedModel { prompt, tools ->
            val name = tools.single().name
            if (prompt.messages.toolResults.isNotEmpty()) flowOf(StreamFrame.End())
            else if (name == ToolIdentity(PluginInstanceId("kinuvo.core"), "memory").modelAlias()) flowOf(
                StreamFrame.ToolCallComplete("same-id", name, """{"action":"replace","target":"memory","old_text":"Use examples","content":"Use examples"}"""), StreamFrame.End(),
            ) else flowOf(
                StreamFrame.ToolCallComplete("same-id", name, """{"action":"create","type":"person","title":"Ana","reason":"Family","evidence":"user-0"}"""), StreamFrame.End(),
            )
        }
        val result = reflection.reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Ana is my sister. Examples still help.")))
        assertEquals(null, result.error)
        assertEquals(1, result.memory)
        assertTrue(result.memoryChanges.isEmpty())
        assertEquals(1, result.proposals.size)
        assertEquals(4, result.measurements.size)
    }

    @Test
    fun warmStagesCannotExecuteToolsFromAnotherStageEvenWhenAdvertised() = runTest {
        val memory = ToolIdentity(PluginInstanceId("kinuvo.core"), "memory").modelAlias()
        val schema = ai.koog.agents.core.tools.ToolDescriptor(memory, "parent schema")
        val model = scriptedModel { prompt, tools ->
            assertEquals(listOf(schema), tools)
            val review = prompt.messages.filterIsInstance<Message.User>().last().textContent()
            if ("Perform only the Knowledge stage." in review && prompt.messages.toolResults.isEmpty()) flowOf(
                StreamFrame.ToolCallComplete("blocked", memory, """{"action":"add","target":"user","content":"Must not save"}"""), StreamFrame.End(),
            ) else flowOf(StreamFrame.End())
        }
        val parent = ReflectionContext(home.agent, model, "parent", emptyList(), listOf(schema))
        val result = reflection.reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Review")), parent = parent)
        assertTrue("Unknown tool" in result.error.orEmpty())
        assertEquals(0, result.userNotes)
        assertEquals("", home.docs.read(home.agent.id, AgentDoc.UserNotes))
    }

    @Test
    fun eachRequestCapturesUsageAndFailedRequestsKeepEarlierChanges() = runTest {
        var request = 0
        val model = scriptedModel { _, tools -> flow {
            request++
            val capture = currentCoroutineContext()[TokenUsageCapture]!!
            capture.cached = request
            when (request) {
                1 -> {
                    emit(StreamFrame.ToolCallComplete("save", tools.single().name, """{"action":"add","target":"memory","content":"Use examples"}"""))
                    emit(StreamFrame.End(metaInfo = ResponseMetaInfo.Empty.copy(inputTokensCount = 20, outputTokensCount = 4)))
                }
                2 -> emit(StreamFrame.End(metaInfo = ResponseMetaInfo.Empty.copy(inputTokensCount = 30, outputTokensCount = 2)))
                else -> error("Knowledge provider failed")
            }
        } }
        val result = reflection.reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Examples help")))
        assertEquals(1, result.memory)
        assertEquals(1, result.memoryChanges.size)
        assertEquals("Knowledge provider failed", result.error)
        assertEquals(listOf(true, true, false), result.measurements.map { it.success })
        assertEquals(listOf(20, 30, null), result.measurements.map { it.inputTokens })
        assertEquals(listOf(4, 2, null), result.measurements.map { it.outputTokens })
        assertEquals(listOf(1, 2, 3), result.measurements.map { it.cachedTokens })
        assertTrue(result.measurements.all { it.kind == RequestKind.Reflection })
    }
}
