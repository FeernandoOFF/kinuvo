package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.knowledge.Knowledge
import kinuvo.harness.core.knowledge.Reflection
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class AgentTracingTest {
    private val home = TestHome()
    @AfterTest
    fun cleanup() = deleteTree(home.files.home)

    @Test
    fun streamedTurnExportsRelatedAgentLlmAndToolSpansWithoutPayloads() = runBlocking {
        val session = home.store.createSession(home.agent, "private-system-secret")
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "private-description-secret")
            override suspend fun execute(args: JsonObject) = "private-tool-result-secret"
        }
        var step = 0
        val model = scriptedModel { _, _ ->
            if (step++ == 0) flowOf(StreamFrame.ToolCallComplete("c1", "read", """{"key":"private-argument-secret"}"""), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("private-response-secret"), StreamFrame.End())
        }
        localTracing(home.files).use { tracing ->
            val events = AgentRun(home.agent, home.store, model, listOf(tool), tracing = tracing)
                .run(session.id, "private-message-secret", runId = "run-1").toList()
            assertIs<AgentEvent.RunFinished>(events.last())
            val spans = spans()
            val agent = spans.single { it["name"]?.jsonPrimitive?.content == "invoke_agent ${home.agent.id}" }
            val llm = spans.filter { it["name"]?.jsonPrimitive?.content == "chat scripted" }
            val call = spans.single { it["name"]?.jsonPrimitive?.content == "execute_tool read" }
            assertEquals(2, llm.size)
            assertEquals(1, (llm + call + agent).map { it.getValue("traceId").jsonPrimitive.content }.distinct().size)
            assertTrue((llm + call).all { it["parentSpanId"]?.jsonPrimitive?.content?.isNotBlank() == true })
            val attributes = records().flatMap { it.getValue("resourceSpans").jsonArray }.map { it.jsonObject.getValue("resource").jsonObject }
                .flatMap { it.getValue("attributes").jsonArray }.map { it.jsonObject }.associate { it.getValue("key").jsonPrimitive.content to it.getValue("value").jsonObject["stringValue"]?.jsonPrimitive?.content }
            assertEquals("kinuvo", attributes["service.name"])
            assertEquals(home.agent.id, attributes["kinuvo.agent.id"])
            assertEquals(session.id, attributes["kinuvo.session.id"])
            assertEquals("run-1", attributes["kinuvo.run.id"])
            assertEquals("Chat", attributes["kinuvo.request.kind"])
            assertFalse("private-" in contents())
            assertFalse("gen_ai.input.messages" in contents())
            assertFalse("gen_ai.tool.call.arguments" in contents())
        }
        val directory = Paths.get(home.files.traces.toString())
        if (directory.fileSystem.supportedFileAttributeViews().contains("posix"))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
    }

    @Test
    fun failuresAndCancellationKeepStatusWithoutExceptionText() = runBlocking {
        localTracing(home.files).use { tracing ->
            val session = home.store.createSession(home.agent, "")
            val model = scriptedModel { _, _ -> flow { emit(StreamFrame.TextDelta("private-partial-secret")); error("private-exception-secret") } }
            val events = AgentRun(home.agent, home.store, model, emptyList(), tracing = tracing).run(session.id, "private-message-secret").toList()
            assertIs<AgentEvent.RunError>(events.last())
            val cancelled = scriptedModel { _, _ -> flow { emit(StreamFrame.TextDelta("private-cancelled-secret")); awaitCancellation() } }
            AgentRun(home.agent, home.store, cancelled, emptyList(), tracing = tracing).run(session.id, "cancel").first { it is AgentEvent.TextMessageContent }
            val llm = spans().filter { it["name"]?.jsonPrimitive?.content == "chat scripted" }
            assertEquals(2, llm.size)
            assertTrue(llm.all { it["status"]?.jsonObject?.get("code")?.jsonPrimitive?.int == 2 })
            assertFalse("private-" in contents())
            assertTrue(spans().all { it["status"]?.jsonObject?.get("message")?.jsonPrimitive?.content.isNullOrEmpty() })
            assertTrue(spans().all { it["events"]?.jsonArray.isNullOrEmpty() })
        }
    }

    @Test
    fun failedAndCancelledToolsKeepErrorStatusWithoutPayloads() = runBlocking {
        localTracing(home.files).use { tracing ->
            for (cancel in listOf(false, true)) {
                val session = home.store.createSession(home.agent, "")
                val tool = object : Tool {
                    override val descriptor = ToolDescriptor("read", "Read")
                    override suspend fun execute(args: JsonObject): String =
                        if (cancel) awaitCancellation() else error("private-tool-exception-secret")
                }
                var step = 0
                val model = scriptedModel { _, _ ->
                    if (step++ == 0) flowOf(StreamFrame.ToolCallComplete("c1", "read", "{}"), StreamFrame.End()) else flowOf(StreamFrame.End())
                }
                val run = AgentRun(home.agent, home.store, model, listOf(tool), tracing = tracing).run(session.id, "go")
                if (cancel) run.first { it is AgentEvent.ToolCallStart } else assertIs<AgentEvent.RunFinished>(run.toList().last())
            }
            val calls = spans().filter { it["name"]?.jsonPrimitive?.content == "execute_tool read" }
            assertEquals(2, calls.size)
            assertTrue(calls.all { it["status"]?.jsonObject?.get("code")?.jsonPrimitive?.int == 2 })
            assertFalse("private-" in contents())
        }
    }

    @Test
    fun reflectionUsesTheSourceSessionIdentity() = runBlocking {
        val knowledge = Knowledge({ MarkdownKnowledgeSource(Path(home.files.home, "vault")) }, FileKnowledgeChangeLog(home.files.knowledgeChanges))
        localTracing(home.files).use { tracing ->
            val model = scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("private-reflection-secret"), StreamFrame.End()) }
            val result = Reflection(knowledge, home.docs, tracing = tracing).reflect(home.agent, model, "source-session",
                listOf(SessionEntry.UserMessage("private-memory-secret")))
            assertNull(result.error)
            assertTrue(spans().any { it["name"]?.jsonPrimitive?.content == "chat scripted" })
            assertTrue("source-session" in contents())
            assertTrue("Reflection" in contents())
            assertFalse("private-" in contents())
        }
    }

    @Test
    fun rotationStaysBoundedAcrossConcurrentRunsAndRestarts() = runBlocking {
        repeat(2) {
            FileTracing(home.files, limit = 1024).use { tracing ->
                val model = scriptedModel { _, _ -> flowOf(StreamFrame.End()) }
                coroutineScope {
                    (1..8).map { async {
                        val session = home.store.createSession(home.agent, "")
                        AgentRun(home.agent, home.store, model, emptyList(), tracing = tracing).run(session.id, "go").toList()
                    } }.awaitAll()
                }
            }
            val files = home.files.traces.children()
            assertTrue(files.size <= 4)
            assertTrue(files.all { it.name.matches(Regex("spans-[0-3]\\.jsonl")) })
            assertTrue(spans().isNotEmpty())
        }
    }

    private fun contents() = home.files.traces.children().filter { it.name.endsWith(".jsonl") }.joinToString("") { it.readText() }
    private fun records() = contents().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
    private fun spans() = records().flatMap { it.getValue("resourceSpans").jsonArray }.flatMap { it.jsonObject.getValue("scopeSpans").jsonArray }
        .flatMap { it.jsonObject.getValue("spans").jsonArray }.map { it.jsonObject }
}
