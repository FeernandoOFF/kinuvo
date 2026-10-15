package kinuvo.harness.executor

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentRunner
import kinuvo.harness.core.ApprovalBroker
import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.Approvals
import kinuvo.harness.core.Model
import kinuvo.harness.core.RunContext
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileStore
import kinuvo.harness.core.store.FileTrustRules
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.ownedBy
import kinuvo.harness.core.modelAlias
import kinuvo.harness.core.tools.ToolRun
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Risk
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RunCodeTest {
    private val files = AgentFiles(Path(SystemTemporaryDirectory, "kinuvo-test-${Random.nextLong().toULong().toString(16)}"))
    private val store = FileStore(files)

    private class FakeTool(name: String, override val risk: Risk, val output: (JsonObject) -> String) : Tool {
        val calls = mutableListOf<JsonObject>()
        override val identity get() = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = ToolDescriptor(name, "fake")
        override suspend fun execute(args: JsonObject) = output(args).also { calls += args }
    }

    private val search = FakeTool("web_search", Risk.Low) {
        """[{"title":"Kotlin 3","score":0.9},{"title":"Old news","score":0.1},{"title":"GraalJS","score":0.7}]"""
    }
    private val save = FakeTool("memory", Risk.Medium) { "Saved: ${it["content"]?.jsonPrimitive?.content}" }

    private val code = """
        const hits = await tools.web_search({query: 'kotlin'})
        const saved = []
        for (const h of hits.filter(h => h.score > 0.5)) saved.push(await tools.memory({action: 'add', content: h.title}))
        console.log('kept', saved.length, 'of', hits.length)
        return saved
    """.trimIndent()

    @Test
    fun capsCombinedOutputIncludingErrorsAndCallSummary() = runBlocking {
        val run = object : ToolRun {
            override val tools = listOf(ToolDescriptor("echo", "echo"))
            override suspend fun call(name: String, args: String) = SessionEntry.ToolResult("inner", name, "ok")
        }
        val tool = RunCodeTool(Limits(output = 300))
        fun script(code: String) = JsonObject(mapOf("code" to kotlinx.serialization.json.JsonPrimitive(code)))
        val result = tool.execute(script("await tools.echo({}); console.log('x'.repeat(500)); return 'y'.repeat(500)"), "c", run)
        assertTrue(result.length <= 300)
        assertContains(result, "Tool calls (1):")
        val error = assertFailsWith<IllegalStateException> {
            tool.execute(script("await tools.echo({}); throw 'x'.repeat(1000000)"), "c", run)
        }
        assertTrue("Error: ${error.message}".length <= 300)
        assertContains(error.message.orEmpty(), "Tool calls (1):")
    }

    @Test
    fun scriptCallsTwoToolsWithApprovals() = runBlocking {
        val agent = store.agents().single().let { it.copy(config = it.config.copy(tools = it.config.tools + ToolNames.RUN_CODE)) }
        val tools = ExecutorPlugin().prepare(RunContext(agent, null), activate = true).ownedBy(kinuvo.protocol.PluginInstanceId("kinuvo.executor")).tools + listOf(search, save)
        val model = Model { _, history, _ ->
            if (history.none { it is SessionEntry.ToolResult }) flowOf(StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.executor"), "run_code").modelAlias(), args(code)), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("Saved what mattered."), StreamFrame.End())
        }
        val broker = ApprovalBroker()
        val session = store.createSession(agent, "")
        val events = mutableListOf<AgentEvent>()
        AgentRunner(agent, store, model, tools, approvals = Approvals(ApprovalPolicy(agent, FileTrustRules(files)), broker))
            .run(session.id, "Search kotlin and remember the good hits").collect {
                events += it
                if (it is AgentEvent.ApprovalRequested) broker.respond(it.approvalId, if ("GraalJS" in it.args) ApprovalDecision.Deny("not that one") else ApprovalDecision.AllowOnce)
            }
        val entries = store.entries(agent, session.id)
        println(transcript(events, entries))

        assertEquals(1, search.calls.size)
        assertEquals(listOf("Kotlin 3"), save.calls.map { it["content"]!!.jsonPrimitive.content })
        val approvals = events.filterIsInstance<AgentEvent.ApprovalRequested>()
        assertEquals(listOf("memory", "memory"), approvals.map { it.toolCallName })
        assertTrue(approvals.all { it.toolCallId == "c1" }, "Approvals show on the Script's call")
        // The denied second save throws inside the Script, which fails it; the inner calls stay out of the history.
        val result = entries.filterIsInstance<SessionEntry.ToolResult>().single()
        assertTrue(result.isError)
        assertContains(result.output, "denied")
        assertContains(result.output, "Tool calls (3):")
        assertEquals(listOf(kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.executor"), "run_code").modelAlias()), entries.filterIsInstance<SessionEntry.ToolCall>().map { it.name })
        assertEquals(listOf("c1", "c1-1", "c1-2", "c1-3"), events.filterIsInstance<AgentEvent.ToolCallStart>().map { it.toolCallId })
        assertTrue(events.last() is AgentEvent.RunFinished)
    }

    @Test
    fun approvedScriptReturnsOnlyItsResult() = runBlocking {
        val agent = store.agents().single().let { it.copy(config = it.config.copy(tools = it.config.tools + ToolNames.RUN_CODE)) }
        val tools = ExecutorPlugin().prepare(RunContext(agent, null), activate = true).ownedBy(kinuvo.protocol.PluginInstanceId("kinuvo.executor")).tools + listOf(search, save)
        var replied = ""
        val model = Model { _, history, _ ->
            val result = history.filterIsInstance<SessionEntry.ToolResult>().lastOrNull()
            if (result == null) flowOf(StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.executor"), "run_code").modelAlias(), args(code)), StreamFrame.End())
            else { replied = result.output; flowOf(StreamFrame.TextDelta("Saved 2."), StreamFrame.End()) }
        }
        val broker = ApprovalBroker()
        val session = store.createSession(agent, "")
        val events = mutableListOf<AgentEvent>()
        AgentRunner(agent, store, model, tools, approvals = Approvals(ApprovalPolicy(agent, FileTrustRules(files)), broker))
            .run(session.id, "Search kotlin and remember the good hits").collect {
                events += it
                if (it is AgentEvent.ApprovalRequested) broker.respond(it.approvalId, ApprovalDecision.AllowOnce)
            }
        println(transcript(events, store.entries(agent, session.id)))
        assertEquals(2, save.calls.size)
        assertTrue(replied.startsWith("[\n  \"Saved: Kotlin 3\",\n  \"Saved: GraalJS\"\n]"), replied)
        assertContains(replied, "Console:\nkept 2 of 3")
        assertContains(replied, "- memory({\"action\":\"add\",\"content\":\"GraalJS\"}) → ok")
    }

    private fun args(code: String) = JsonObject(mapOf("code" to kotlinx.serialization.json.JsonPrimitive(code))).toString()

    private fun transcript(events: List<AgentEvent>, entries: List<SessionEntry>) = buildString {
        appendLine("--- events ---")
        events.forEach { e ->
            when (e) {
                is AgentEvent.ToolCallStart -> appendLine("call ${e.toolCallId} ${e.toolCallName} ${e.args.take(80)}")
                is AgentEvent.ToolCallResult -> appendLine("result ${e.toolCallId}${if (e.isError) " (error)" else ""}: ${e.content.take(80).replace("\n", " ")}")
                is AgentEvent.ApprovalRequested -> appendLine("approval? on ${e.toolCallId}: ${e.toolCallName} ${e.args} [${e.risk}]")
                is AgentEvent.ApprovalResolved -> appendLine("approval ${if (e.allowed) "allowed" else "denied"}")
                is AgentEvent.TextMessageEnd -> appendLine("assistant: ${e.text}")
                else -> Unit
            }
        }
        appendLine("--- transcript ---")
        entries.forEach { e ->
            when (e) {
                is SessionEntry.UserMessage -> appendLine("user: ${e.text}")
                is SessionEntry.ToolCall -> appendLine("tool call ${e.name}: ${e.args}")
                is SessionEntry.ToolResult -> appendLine("tool result${if (e.isError) " (error)" else ""}:\n${e.output}")
                is SessionEntry.AssistantMessage -> appendLine("assistant: ${e.text}")
                else -> Unit
            }
        }
    }
}
