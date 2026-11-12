package kinuvo.server

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginHost
import kinuvo.server.plugin.deleteTree
import kinuvo.harness.builtin.*
import kinuvo.server.store.*
import kinuvo.server.plugin.ProviderTask
import kinuvo.server.plugin.TaskChange
import kinuvo.server.plugin.TaskList
import kinuvo.server.plugin.TaskApp
import kinuvo.server.plugin.TaskProvider
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.uuid.Uuid

class TaskProviderSettingsTest {
    private val files = AgentFiles(Path(SystemTemporaryDirectory, "task-settings-${Uuid.random()}"))

    /** Lists one list named after the token its host gives it. */
    private object Remote : TaskProvider {
        override val id = "remote"
        override val name = "Remote"
        override val settings = SettingsSchema(listOf(SettingsField("token", "API token", required = true, sensitive = true)))
        override val hostAccess = PluginAccess(secrets = setOf("token"))
        override fun configured(options: Map<String, String>, host: PluginHost) = object : TaskApp {
            override suspend fun lists() = listOf(TaskList("1", host.secret("token")))
            override suspend fun tasks(listId: String, includeDone: Boolean) = emptyList<ProviderTask>()
            override suspend fun task(id: String): ProviderTask? = null
            override suspend fun create(listId: String, title: String, notes: String, due: LocalDate?) = error("unused")
            override suspend fun update(taskId: String, change: TaskChange) = error("unused")
        }
    }

    private val plugin = object : HarnessPlugin {
        override val definition = PluginDefinition("test.tasksettings", "Test")
        override val taskProviders = listOf<TaskProvider>(Remote)
    }

    @AfterTest fun cleanup() = deleteTree(files.home)

    @Test fun savesOptionsMasksSecretsAndBindsThemToTheProvider() = runTest {
        val installation = ServerInstallation.assemble(files, plugins = listOf(plugin))
        try {
            val server = installation.settings
            assertEquals("Remote", server.tasksSetup().providers.first { it.id == "remote" }.name)
            assertEquals("API token is required", assertFailsWith<IllegalArgumentException> { server.saveTasksSettings(TasksSettings("remote")) }.message)
            assertEquals("API token is required", assertFailsWith<IllegalArgumentException> { installation.tasks.providerLists("remote") }.message)
            assertEquals(listOf("draft"), server.taskProviderLists(TaskProviderRef("remote", emptyMap(), mapOf("token" to SecretUpdate.Replace("draft")))).map { it.name })

            server.saveTasksSettings(TasksSettings("remote"), mapOf("token" to SecretUpdate.Replace("tk_1")))
            val setup = server.tasksSetup()
            assertEquals(mapOf("token" to KnowledgeSettings.SECRET), setup.settings.options)
            assertEquals(setOf("token"), setup.configuredSecrets)
            assertEquals(listOf("tk_1"), installation.tasks.lists().map { it.name })
            assertEquals(listOf("tk_1"), server.taskProviderLists(TaskProviderRef("remote", emptyMap(), mapOf("token" to SecretUpdate.Keep))).map { it.name })

            server.saveTasksSettings(TasksSettings("remote", pollMinutes = 9), mapOf("token" to SecretUpdate.Keep))
            assertEquals("tk_1", files.tasksConfig().options["token"])
            server.saveTasksSettings(TasksSettings("remote"), mapOf("token" to SecretUpdate.Replace("\${op://Kinuvo/Remote/token}")))
            assertEquals(mapOf("token" to "\${op://Kinuvo/Remote/token}"), server.tasksSetup().settings.options)
            assertEquals("API token is required", assertFailsWith<IllegalArgumentException> {
                server.saveTasksSettings(TasksSettings("remote"), mapOf("token" to SecretUpdate.Remove))
            }.message)
            assertEquals("Use a secret update for sensitive settings", assertFailsWith<IllegalArgumentException> {
                server.saveTasksSettings(TasksSettings("remote", options = mapOf("token" to "x")))
            }.message)
        } finally { installation.resources.close() }
    }
}
