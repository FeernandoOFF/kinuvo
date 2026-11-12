package kinuvo.plugins.exec

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.PluginStorage
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecCheckTest {
    private val files = TestStorage(Path(Files.createTempDirectory("harness-test").toFile().path))
    private val check = assertNotNull(ExecPlugin(files).execCheck)
    private val root = Files.createTempDirectory("ws").toFile().canonicalFile

    @Test
    fun listsTheWorkspaceAndRunsTheCommand() = runTest {
        File(root, "notes.md").writeText("hi")
        File(root, "src").mkdirs()
        val r = check.check(Agent("main", "Kinuvo", AgentConfig(exec = "host", workspace = listOf(root.path))), "echo ok")
        assertNull(r.error)
        assertEquals(listOf("notes.md", "src/"), r.files)
        assertEquals("ok", r.output?.trim())
        assertEquals(0, r.exitCode)
    }

    @Test
    fun reportsSettingsThatCannotWork() = runTest {
        val missing = check.check(Agent("main", "Kinuvo", AgentConfig(exec = "host", workspace = listOf("$root/nope"))), null)
        assertTrue("does not exist" in missing.error.orEmpty())
        assertTrue("ssh" in check.check(Agent("main", "Kinuvo", AgentConfig(exec = "ssh", workspace = listOf("/tmp"))), null).error.orEmpty())
    }
}

private class TestStorage(override val home: Path) : PluginStorage {
    override val sharedMcp = Path(home, "mcp.json")
    override fun mcp(agentId: String) = Path(home, "agents", agentId, "mcp.json")
    override fun agentExists(id: String) = true
    override fun loadEnv(agentId: String?, process: Map<String, String>) = process
    override fun knowledgeOptions() = emptyMap<String, String>()
}
