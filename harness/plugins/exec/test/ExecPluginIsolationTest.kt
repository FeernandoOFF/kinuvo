package kinuvo.harness.exec

import kinuvo.harness.core.*
import kinuvo.harness.core.store.AgentFiles
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ExecPluginIsolationTest {
    @Test fun execOverrideDisablesToolsAndResetRestoresHostWithoutAffectingAnotherSession() = runTest {
        val root = Files.createTempDirectory("exec-session-reset").toFile()
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        File(first, "value.txt").writeText("first")
        File(second, "value.txt").writeText("second")
        val plugin = ExecPlugin(AgentFiles(Path(root.path, "home")))
        val defaults = Agent("same", "Same", AgentConfig(exec = "host", workspace = listOf(first.path)))
        val other = defaults.with(kinuvo.protocol.SessionSettings(workspace = listOf(second.path)))
        try {
            val secondSession = async {
                plugin.prepare(RunContext(other, "second"), true)
                plugin.readFile(other, "value.txt")
            }
            val disabled = defaults.with(kinuvo.protocol.SessionSettings(exec = "none"))
            assertTrue(plugin.prepare(RunContext(disabled, "first"), true).tools.isEmpty())
            assertNull(plugin.readFile(disabled, "value.txt"))
            assertEquals("second", secondSession.await())
            val reset = defaults.with(kinuvo.protocol.SessionSettings())
            val restored = plugin.prepare(RunContext(reset, "first"), true)
            assertTrue(restored.tools.isNotEmpty())
            assertEquals("first", plugin.readFile(reset, "value.txt"))
            val shell = restored.tools.single { it.descriptor.name == "shell" }
            val result = shell.execute(kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive("cat value.txt")) })
            assertTrue("first" in result)
            assertEquals("second", plugin.readFile(other, "value.txt"))
            assertEquals("host", defaults.config.exec)
        } finally { plugin.close(); root.deleteRecursively() }
    }

    @Test fun simultaneousSessionConfigurationsKeepTheirOwnWorkspaceAndCapabilities() = runTest {
        val root = Files.createTempDirectory("exec-session-isolation").toFile()
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        File(first, "value.txt").writeText("first")
        File(second, "value.txt").writeText("second")
        val plugin = ExecPlugin(AgentFiles(Path(root.path, "home")))
        val agent = Agent("same", "Same", AgentConfig(exec = "host", workspace = listOf(first.path)))
        val other = agent.with(kinuvo.protocol.SessionSettings(workspace = listOf(second.path)))
        try {
            val one = async { plugin.prepare(RunContext(agent, "first"), true); plugin.readFile(agent, "value.txt") }
            val two = async { plugin.prepare(RunContext(other, "second"), true); plugin.readFile(other, "value.txt") }
            assertEquals("first", one.await())
            assertEquals("second", two.await())
            assertEquals("first", plugin.readFile(agent, "value.txt"))
            assertEquals("second", plugin.readFile(other, "value.txt"))
            assertFailsWith<IllegalArgumentException> { plugin.readFile(agent, File(second, "value.txt").path) }
            val missing = agent.with(kinuvo.protocol.SessionSettings(workspace = listOf(File(root, "missing").path)))
            assertTrue(plugin.prepare(RunContext(missing, "missing"), true).tools.isEmpty())
            assertTrue(plugin.prepare(RunContext(agent, "first"), false).catalog.all { it.state.actionable })
            assertTrue(plugin.prepare(RunContext(missing, "missing"), false).catalog.none { it.state.actionable })
        } finally { plugin.close(); root.deleteRecursively() }
    }
}
