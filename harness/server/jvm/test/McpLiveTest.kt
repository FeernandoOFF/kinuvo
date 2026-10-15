package kinuvo.harness.server

import kinuvo.harness.mcp.*

import kinuvo.protocol.AgentEvent
import kinuvo.harness.server.Harness
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Needs network + npx; spawns the reference `server-everything` MCP server over stdio. */
class McpLiveTest {
    private val config = """{"mcpServers": {"everything": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-everything"]}}}"""

    @Test
    fun listsAndCallsStdioServerTools() = runBlocking {
        if (System.getenv("KINUVO_LIVE") != "1") return@runBlocking
        val file = Files.createTempFile("mcp", ".json").toFile().apply { writeText(config) }
        val manager = McpManager(listOf(Path(file.path)))
        runBlocking {
            try {
                val (tools, warnings) = manager.tools()
                assertEquals(emptyList(), warnings)
                val echo = tools.single { it.descriptor.name == "everything__echo" }
                assertTrue("hello mcp" in echo.execute(buildJsonObject { put("message", "hello mcp") }))
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun codexUsesMcpTool() = runBlocking {
        if (System.getenv("KINUVO_LIVE") != "1") return@runBlocking
        val home = Files.createTempDirectory("harness-live-mcp").toFile()
        File(home, "mcp.json").writeText(config.replace("\"args\"", "\"includeTools\": [\"echo\"], \"args\""))
        val harness = Harness(Path(home.path))
        val agent = harness.store.agents().single().let { it.copy(config = it.config.copy(reasoning = "low")) }
        val session = harness.newSession(agent)
        runBlocking {
            try {
                val events = harness.runner(agent).run(session.id, "Use the echo tool to echo 'pineapple-42', then tell me what it returned.").toList()
                val calls = events.filterIsInstance<AgentEvent.ToolCallStart>()
                assertTrue(calls.any { it.toolCallName == "everything__echo" }, "calls: $calls, events: $events")
                assertTrue(events.filterIsInstance<AgentEvent.TextMessageEnd>().any { "pineapple-42" in it.text })
            } finally {
                harness.release(agent)
            }
        }
    }
}
