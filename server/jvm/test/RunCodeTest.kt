package kinuvo.plugins.executor

import kinuvo.server.plugin.toolSpec
import kinuvo.testing.server.AgentRun
import kinuvo.testing.harness.*

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.builtin.AgentRun
import kinuvo.server.ApprovalBroker
import kinuvo.server.ApprovalPolicy
import kinuvo.server.Approvals
import kinuvo.server.plugin.RunContext
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileStore
import kinuvo.server.store.FileTrustRules
import kinuvo.server.plugin.Tool
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.ownedBy
import kinuvo.server.plugin.withModelNames
import kinuvo.server.plugin.ToolRun
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.approval.approval
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.ToolCallId
import kotlinx.coroutines.flow.flowOf
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
        override val identity get() = kinuvo.protocol.plugin.ToolIdentity(kinuvo.protocol.plugin.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = toolSpec(name, "fake")
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
            override val tools = listOf(toolSpec("echo", "echo"))
            override suspend fun call(name: String, args: String) = SessionEntry.ToolResult(ToolCallId("inner"), name, "ok")
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
        val tools = ExecutorPlugin().prepare(RunContext(agent, null), activate = true).ownedBy(kinuvo.protocol.plugin.PluginInstanceId("kinuvo.executor")).tools.plus(listOf(search, save)).withModelNames()
        val model = scriptedModel { prompt, _ ->
            val history = prompt.messages
            if (history.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", "run_code", args(code)), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("Saved what mattered."), StreamFrame.End())
        }
        val broker = ApprovalBroker()
        val session = store.createSession(agent.id, "")
        val events = mutableListOf<AgentEvent>()
        AgentRun(agent, store, model, tools, approvals = Approvals(ApprovalPolicy(agent, FileTrustRules(files)), broker))
            .run(session.id, "Search kotlin and remember the good hits").collect {
                events += it
                it.approval?.let { approval -> broker.respond(approval.id, if ("GraalJS" in approval.args) ApprovalDecision.Deny("not that one") else ApprovalDecision.AllowOnce) }
            }
        val entries = store.entries(agent.id, session.id)
        println(transcript(events, entries))

        assertEquals(1, search.calls.size)
        assertEquals(listOf("Kotlin 3"), save.calls.map { it["content"]!!.jsonPrimitive.content })
        val approvals = events.mapNotNull { it.approval }
        assertEquals(listOf("memory", "memory"), approvals.map { it.toolCallName })
        assertTrue(approvals.all { it.toolCallId.value == "c1" }, "Approvals show on the Script's call")
        // The denied second save throws inside the Script, which fails it; the inner calls stay out of the history.
        val result = entries.filterIsInstance<SessionEntry.ToolResult>().single()
        assertTrue(result.isError)
        assertContains(result.output, "denied")
        assertContains(result.output, "Tool calls (3):")
        assertEquals(listOf("run_code"), entries.filterIsInstance<SessionEntry.ToolCall>().map { it.name })
        assertEquals(listOf("c1", "c1-1", "c1-2", "c1-3"), events.filterIsInstance<AgentEvent.ToolCallStart>().map { it.toolCallId.value })
        assertTrue(events.last() is AgentEvent.RunFinished)
    }

    @Test
    fun approvedScriptReturnsOnlyItsResult() = runBlocking {
        val agent = store.agents().single().let { it.copy(config = it.config.copy(tools = it.config.tools + ToolNames.RUN_CODE)) }
        val tools = ExecutorPlugin().prepare(RunContext(agent, null), activate = true).ownedBy(kinuvo.protocol.plugin.PluginInstanceId("kinuvo.executor")).tools.plus(listOf(search, save)).withModelNames()
        var replied = ""
        val model = scriptedModel { prompt, _ ->
            val history = prompt.messages
            val result = history.toolResults.lastOrNull()
            if (result == null) flowOf(StreamFrame.ToolCallComplete("c1", "run_code", args(code)), StreamFrame.End())
            else { replied = result.output; flowOf(StreamFrame.TextDelta("Saved 2."), StreamFrame.End()) }
        }
        val broker = ApprovalBroker()
        val session = store.createSession(agent.id, "")
        val events = mutableListOf<AgentEvent>()
        AgentRun(agent, store, model, tools, approvals = Approvals(ApprovalPolicy(agent, FileTrustRules(files)), broker))
            .run(session.id, "Search kotlin and remember the good hits").collect {
                events += it
                it.approval?.let { approval -> broker.respond(approval.id, ApprovalDecision.AllowOnce) }
            }
        println(transcript(events, store.entries(agent.id, session.id)))
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
                is AgentEvent.InteractionRequested -> e.approval?.let { appendLine("approval? on ${it.toolCallId}: ${it.toolCallName} ${it.args} [${it.risk}]") }
                is AgentEvent.InteractionResolved -> appendLine("approval ${if (e.allowed == true) "allowed" else "denied"}")
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
