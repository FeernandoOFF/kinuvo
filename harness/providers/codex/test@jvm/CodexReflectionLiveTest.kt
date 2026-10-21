package kinuvo.harness.codex

import kinuvo.harness.core.*
import kinuvo.harness.core.knowledge.*
import kinuvo.harness.core.skills.*
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tools.MemoryTool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ReflectionResult
import kinuvo.protocol.RequestMetrics
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class CodexReflectionLiveTest {
    @Test
    fun comparesChatAndSmallReflectionModels() = runBlocking {
        if (System.getenv("KINUVO_LIVE") != "1") return@runBlocking
        val root = Path(Files.createTempDirectory("kinuvo-reflection-benchmark").toString())
        val provider = CodexProvider()
        try {
            val catalog = provider.models()
            val chatId = System.getenv("KINUVO_MODEL") ?: AgentConfig().model
            val smallId = System.getenv("KINUVO_SMALL_MODEL") ?: "gpt-6-luna"
            val config = AgentConfig(model = chatId, reasoning = "low", tools = listOf(ToolNames.MEMORY, ToolNames.KNOWLEDGE, ToolNames.SKILLS))
            val agent = Agent("benchmark", "Benchmark", config)
            fun model(agent: Agent) = provider.model(agent.config, catalog.firstOrNull { it.id == agent.config.model })
            val chatModel = model(agent)
            val chat = fixture(Path(root, "chat"), agent)
            val store = FileStore(chat.files)
            val session = store.createSession(agent, "You are a concise personal Agent. Help with projects and follow background Reflection instructions when asked.")
            history.forEach { store.append(agent, session.id, it) }
            val tools = PluginTools(listOf(MemoryTool(agent, chat.docs), ActivateSkillTool(agent, chat.skills),
                ReadSkillFileTool(agent, chat.skills), SkillManageTool(agent, chat.skills)))
                .ownedBy(PluginInstanceId("kinuvo.core")).tools +
                PluginTools(listOf(KnowledgeTool(chat.knowledge, ChangeSource(agent.id, session.id))))
                    .ownedBy(PluginInstanceId("kinuvo.knowledge")).tools
            val started = TimeSource.Monotonic.markNow()
            val events = AgentRun(agent, store, chatModel, tools).run(session.id, "Those release checks worked. Use that procedure for future releases. " +
                "Please recap Harbour's owner and deadline and my communication preference in two sentences, without calling tools.").toList()
            val chatMs = started.elapsedNow().inWholeMilliseconds
            val entries = store.entries(agent, session.id)
            val rows = mutableListOf(Row("chat", chatId, entries.filterIsInstance<SessionEntry.Measurement>().map { it.metrics }, chatMs))
            assertIs<AgentEvent.RunFinished>(events.last(), "Chat failed: $events")
            val variants = listOf("cold big" to agent, "small (opt-in)" to agent.copy(config = config.copy(model = smallId)))
            for ((label, measuredAgent) in variants) {
                val fixture = fixture(Path(root, label), measuredAgent)
                val start = TimeSource.Monotonic.markNow()
                val result = Reflection(fixture.knowledge, fixture.docs, fixture.skills).reflect(
                    measuredAgent, if (label == "cold big") chatModel else model(measuredAgent), session.id, entries,
                )
                rows += Row(label, measuredAgent.config.model, result.measurements, start.elapsedNow().inWholeMilliseconds, result)
            }
            println("\nReflection benchmark: identical seeded Session and fresh repositories per variant; reasoning=low.")
            println("Opt-in small model: $smallId (KINUVO_SMALL_MODEL).")
            println("Both variants use fresh compact prompts; provider caching can still occur. '?' means usage unavailable; reasoning is included in output.")
            val table = listOf(listOf("variant", "model", "requests", "input", "cached", "output", "reasoning", "wall ms", "memory/user", "proposals", "errors")) + rows.map { row ->
                val result = row.result
                listOf(row.label, row.model, row.metrics.size.toString(), row.total { it.inputTokens }, row.total { it.cachedTokens },
                    row.total { it.outputTokens }, row.total { it.reasoningTokens }, row.ms.toString(),
                    result?.let { "${it.memory}/${it.userNotes}" } ?: "-", result?.proposals?.size?.toString() ?: "-",
                    result?.error?.lines()?.size?.toString() ?: "0")
            }
            val widths = table.first().indices.map { column -> table.maxOf { it[column].length } }
            table.forEach { row -> println(row.mapIndexed { index, cell -> cell.padEnd(widths[index]) }.joinToString(" | ")) }
            rows.mapNotNull { row -> row.result?.let { row.label to it } }.forEach { (label, result) ->
                println("\n$label produced:")
                result.memoryChanges.forEach { println("  ${it.target}: ${it.before.trim().replace("\n", "; ")} -> ${it.after.trim().replace("\n", "; ")}") }
                result.proposals.forEach { println("  ${it.tool}: ${it.args} (evidence: ${it.evidence})") }
                println("  errors: ${result.error ?: "none"}")
            }
            // The small model is reported, not judged: it's an opt-in.
            rows.first { it.label == "cold big" }.result?.let { assertNull(it.error) }
            rows.mapNotNull { it.result }.forEach { result ->
                assertTrue(result.measurements.all { it.success }, "Reflection request failed: ${result.measurements}")
            }
        } finally {
            provider.close()
            deleteTree(root)
        }
    }

    private data class Row(val label: String, val model: String, val metrics: List<RequestMetrics>, val ms: Long, val result: ReflectionResult? = null) {
        fun total(value: (RequestMetrics) -> Int?): String {
            val tokens = metrics.map(value)
            return if (tokens.isEmpty() || tokens.any { it == null }) "?" else tokens.sumOf { it!!.toLong() }.toString()
        }
    }

    private data class Fixture(val files: AgentFiles, val docs: FileAgentDocs, val skills: FileSkillRepository, val knowledge: Knowledge)

    private suspend fun fixture(root: Path, agent: Agent): Fixture {
        val files = AgentFiles(root)
        val docs = FileAgentDocs(files)
        docs.write(agent.id, AgentDoc.Memory, "- Confirm module tests before suggesting a release\n")
        docs.write(agent.id, AgentDoc.UserNotes, "- Lives in Cork\n")
        val knowledge = Knowledge({ MarkdownKnowledgeSource(files.defaultKnowledge) }, FileKnowledgeChangeLog(files.knowledgeChanges),
            conventions = { "Use person Notes for people and project Notes for projects. Keep project ownership and deadlines in the project's body." })
        knowledge.create("project", "Harbour", body = "Internal migration project.")
        return Fixture(files, docs, FileSkillRepository(files, userHome = null), knowledge)
    }

    private val history = listOf(
        SessionEntry.UserMessage("I moved from Cork to Dublin last month. I prefer concise answers with one concrete example. Ana is my sister."),
        SessionEntry.AssistantMessage("Understood: Dublin, concise replies and a concrete example. Ana is your sister."),
        SessionEntry.UserMessage("Ana leads our Harbour migration project. We agreed the launch deadline is 15 December 2026; record that ownership and deadline for future planning."),
        SessionEntry.AssistantMessage("I'll use Ana as Harbour's lead and 15 December 2026 as its launch deadline."),
        SessionEntry.UserMessage("Verify the release in order: run ./kotlin test -m core -p jvm, then ./kotlin test -m codex -p jvm. Stop if either fails; don't publish until both pass. Never run an unfiltered build."),
        SessionEntry.ToolCall("core-check", "shell", """{"command":"./kotlin test -m core -p jvm"}"""),
        SessionEntry.ToolResult("core-check", "shell", "Exit 0. All 179 core JVM tests passed."),
        SessionEntry.ToolCall("codex-check", "shell", """{"command":"./kotlin test -m codex -p jvm"}"""),
        SessionEntry.ToolResult("codex-check", "shell", "Exit 0. All Codex JVM tests passed; live tests were gated off."),
        SessionEntry.AssistantMessage("Both checks passed in order. The release verification is complete; nothing has been published."),
    )
}
