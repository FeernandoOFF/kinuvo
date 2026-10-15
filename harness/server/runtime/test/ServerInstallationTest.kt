package kinuvo.harness.server

import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.uuid.Uuid

class ServerInstallationTest {
    @Test fun duplicateNamedInstancesFailDuringAssemblyAndCloseTheirResources() = runTest {
        var closes = 0
        fun plugin() = object : HarnessPlugin {
            override val definition = PluginDefinition("example.plugin", "Example")
            override suspend fun close() { closes++ }
        }
        assertFailsWith<IllegalArgumentException> { ServerInstallation.assemble(AgentFiles(home), plugins = listOf(plugin(), plugin())) }
        assertEquals(2, closes)
    }

    private val home = Path(SystemTemporaryDirectory, "server-installation-${Uuid.random()}")
    @AfterTest
    fun cleanup() = deleteTree(home)

    @Test
    fun failedAssemblyClosesInstalledPluginsOnce() = runTest {
        var closes = 0
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.serverinstallationtest.2", "Test")
            override val modelProviders: List<ModelProvider> get() = error("Assembly failed")
            override suspend fun close() { closes++ }
        }
        assertFailsWith<IllegalArgumentException> { ServerInstallation.assemble(AgentFiles(home), plugins = listOf(plugin, plugin)) }
        assertEquals(1, closes)
    }

    @Test
    fun installationRetainsServerIdentityAndConfigurationOnDisk() = runTest {
        val files = AgentFiles(home)
        var dependencies = ServerInstallation.assemble(files)
        val id = dependencies.serverId
        try {
            val runtime = HarnessRuntime(dependencies)
            runtime.timeZone = TimeZone.of("Europe/Dublin")
            runtime.saveTasksSettings(TasksSettings(pollMinutes = 17))
            runtime.saveKnowledgeSettings(KnowledgeSettings(reflection = false))
        } finally { dependencies.resources.close() }
        dependencies = ServerInstallation.assemble(files)
        try {
            assertEquals(id, dependencies.serverId)
            assertEquals(TimeZone.of("Europe/Dublin"), dependencies.settings.timeZone)
            assertEquals(17, dependencies.settings.tasksSetup().settings.pollMinutes)
            assertFalse(dependencies.settings.reflectionEnabled)
        } finally { dependencies.resources.close() }
    }
}
