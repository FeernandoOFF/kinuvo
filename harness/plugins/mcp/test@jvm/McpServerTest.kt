package kinuvo.harness.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class McpServerTest {
    @Test
    fun failingStdioServerWarningIncludesStderrAndExitCode() = runBlocking {
        val cfg = File(Files.createTempDirectory("mcp").toFile(), "mcp.json").apply {
            writeText("""{"mcpServers": {"bad": {"command": "sh", "args": ["-c", "echo boom-details >&2; exit 3"]}}}""")
        }
        val manager = McpManager(listOf(Path(cfg.path)))
        val (tools, warnings) = manager.tools()
        manager.close()
        assertTrue(tools.isEmpty())
        val w = warnings.single()
        assertTrue("MCP server 'bad' unavailable" in w && "boom-details" in w && "exit code 3" in w, w)
    }
}
