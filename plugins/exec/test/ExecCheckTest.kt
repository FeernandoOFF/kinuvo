package kinuvo.plugins.exec

import kotlin.test.AfterTest
import kinuvo.testing.harness.TemporaryDirectories
import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.PluginStorage
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kinuvo.protocol.agent.AgentId

class ExecCheckTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private val files = TestStorage(Path(temporaryDirectories.create("harness-test").toFile().path))
    private val check = assertNotNull(ExecPlugin(files).execCheck)
    private val root = temporaryDirectories.create("ws").toFile().canonicalFile

    @Test
    fun listsTheWorkspaceAndRunsTheCommand() = runTest {
        File(root, "notes.md").writeText("hi")
        File(root, "src").mkdirs()
        val r = check.check(Agent(AgentId("main"), "Kinuvo", AgentConfig(exec = "host", workspace = listOf(root.path))), "echo ok")
        assertNull(r.error)
        assertEquals(listOf("notes.md", "src/"), r.files)
        assertEquals("ok", r.output?.trim())
        assertEquals(0, r.exitCode)
    }

    @Test
    fun reportsSettingsThatCannotWork() = runTest {
        val missing = check.check(Agent(AgentId("main"), "Kinuvo", AgentConfig(exec = "host", workspace = listOf("$root/nope"))), null)
        assertTrue("does not exist" in missing.error.orEmpty())
        assertTrue("ssh" in check.check(Agent(AgentId("main"), "Kinuvo", AgentConfig(exec = "ssh", workspace = listOf("/tmp"))), null).error.orEmpty())
    }
}

private class TestStorage(override val home: Path) : PluginStorage {
    override val sharedMcp = Path(home, "mcp.json")
    override fun mcp(agentId: AgentId) = Path(home, "agents", agentId.value, "mcp.json")
    override fun agentExists(id: AgentId) = true
    override fun loadEnv(agentId: AgentId?, process: Map<String, String>) = process
    override fun knowledgeOptions() = emptyMap<String, String>()
}
