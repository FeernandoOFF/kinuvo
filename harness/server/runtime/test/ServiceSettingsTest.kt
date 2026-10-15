package kinuvo.harness.server

import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.uuid.Uuid

class ServiceSettingsTest {
    private val files = AgentFiles(Path(SystemTemporaryDirectory, "service-settings-${Uuid.random()}"))
    private var configured = 0
    private var sentOptions = emptyMap<String, String>()
    private val schema = SettingsSchema(listOf(
        SettingsField("topic", "Topic", required = true),
        SettingsField("password", "Password", sensitive = true),
        SettingsField("tokenCount", "Token count", SettingsFieldType.Integer),
        SettingsField("url", "Server URL", SettingsFieldType.Url, defaultValue = "https://example.org"),
        SettingsField("enabled", "Enabled", SettingsFieldType.Boolean),
        SettingsField("format", "Format", SettingsFieldType.Choice, choices = listOf("text", "json")),
    ))
    private fun provider(id: String) = object : NotificationProvider {
        override val id = id
        override val name = "Provider $id"
        override val settings = schema
        override fun configured(options: Map<String, String>, host: kinuvo.harness.core.PluginHost): Notifier {
            configured++
            return Notifier { sentOptions = options }
        }
    }
    private val plugin = object : HarnessPlugin {
        override val definition = kinuvo.protocol.PluginDefinition("test.servicesettingstest.1", "Test")
            override val notifications = listOf(provider("first"), provider("second")) }

    @AfterTest fun cleanup() = deleteTree(files.home)

    private suspend fun settings(block: suspend (ServerSettings) -> Unit) {
        val installation = ServerInstallation.assemble(files, plugins = listOf(plugin))
        try { block(installation.settings) } finally { installation.resources.close() }
    }

    @Test fun discoversSchemaAndMasksDeclaredSecretsWithoutActivating() = runTest {
        files.saveServiceConfig(ServiceKind.Notifications, ServiceSettings("first", mapOf("topic" to "news", "password" to "hidden", "tokenCount" to "7")))
        settings { server ->
            val setup = server.serviceSetup(ServiceKind.Notifications)
            assertEquals(listOf("Provider first", "Provider second"), setup.providers.map { it.name })
            assertEquals(schema, setup.providers.first().schema)
            assertEquals(mapOf("topic" to "news", "password" to KnowledgeSettings.SECRET, "tokenCount" to "7"), setup.settings.options)
            assertEquals(setOf("password"), setup.configuredSecrets)
            assertEquals(0, configured)
        }
    }

    @Test fun savesAndTestsExplicitSecretChangesWithoutPersistingOperations() = runTest {
        val kind = ServiceKind.Notifications
        settings { server ->
            fun save(update: SecretUpdate) = server.saveService(kind, ServiceSettings("first", mapOf("topic" to "news")), mapOf("password" to update))
            save(SecretUpdate.Replace("initial"))
            save(SecretUpdate.Keep)
            assertEquals("initial", files.serviceConfig(kind).options["password"])
            assertEquals(0, configured)
            assertNull(files.serviceConfig(kind).options["url"])
            assertEquals("Sent", server.testService(TestServiceParams(kind, ServiceSettings("first", mapOf("topic" to "draft")),
                secretUpdates = mapOf("password" to SecretUpdate.Replace("temporary")))).summary)
            assertEquals("temporary", sentOptions["password"])
            assertEquals("https://example.org", sentOptions["url"])
            assertEquals("initial", files.serviceConfig(kind).options["password"])
            assertEquals("Sent", server.testService(TestServiceParams(kind, secretUpdates = mapOf("password" to SecretUpdate.Replace("test-only")))).summary)
            assertEquals("test-only", sentOptions["password"])
            save(SecretUpdate.Remove)
            assertFalse("password" in files.serviceConfig(kind).options)
            assertTrue(server.serviceSetup(kind).configuredSecrets.isEmpty())
            assertFalse(files.serverConfig.readTextOrNull().orEmpty().contains("secretUpdates"))
        }
    }

    @Test fun credentialsCannotMoveBetweenProviders() = runTest {
        val kind = ServiceKind.Notifications
        files.saveServiceConfig(kind, ServiceSettings("first", mapOf("topic" to "news", "password" to "first-only")))
        settings { server ->
            val draft = ServiceSettings("second", mapOf("topic" to "news"))
            server.saveService(kind, draft, mapOf("password" to SecretUpdate.Keep))
            assertFalse("password" in files.serviceConfig(kind).options)
            files.saveServiceConfig(kind, ServiceSettings("first", mapOf("topic" to "news", "password" to "first-only")))
            assertFailsWith<IllegalArgumentException> { server.saveService(kind, draft.copy(options = draft.options + ("password" to KnowledgeSettings.SECRET))) }
            assertEquals("first-only", files.serviceConfig(kind).options["password"])
        }
    }

    @Test fun explicitKeepPreservesSecretReferences() = runTest {
        val kind = ServiceKind.Notifications
        val reference = "\${pass://Kinuvo/Notifications/password}"
        files.saveServiceConfig(kind, ServiceSettings("first", mapOf("topic" to "news", "password" to reference)))
        settings { server ->
            assertEquals(reference, server.serviceSetup(kind).settings.options["password"])
            server.saveService(kind, ServiceSettings("first", mapOf("topic" to "updated")), mapOf("password" to SecretUpdate.Keep))
            assertEquals(reference, files.serviceConfig(kind).options["password"])
            assertEquals(0, configured)
        }
    }

    @Test fun rejectsInvalidSettingsBeforeSavingOrStartingResources() = runTest {
        settings { server ->
            for (options in listOf(emptyMap(), mapOf("topic" to "news", "extra" to "x"), mapOf("topic" to "news", "url" to "file:///tmp/x"),
                mapOf("topic" to "news", "enabled" to "yes"), mapOf("topic" to "news", "tokenCount" to "many"), mapOf("topic" to "news", "format" to "xml"))) {
                assertFailsWith<IllegalArgumentException> { server.saveService(ServiceKind.Notifications, ServiceSettings("first", options)) }
            }
            assertFailsWith<IllegalArgumentException> {
                server.saveService(ServiceKind.Notifications, ServiceSettings("first", mapOf("topic" to "news")), mapOf("topic" to SecretUpdate.Replace("private")))
            }
            assertNull(files.serverConfig.readTextOrNull())
            assertEquals(0, configured)
        }
    }

    @Test fun duplicateProviderIdsFailDuringAssembly() = runTest {
        val duplicate = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.servicesettingstest.2", "Test")
            override val notifications = listOf(provider("same"), provider("same")) }
        assertFailsWith<IllegalArgumentException> { ServerInstallation.assemble(files, plugins = listOf(duplicate)) }
    }

    @Test fun interpolatedUrlsAreSavedAndPassedToProvidersWithoutResolvingReferences() = runTest {
        val kind = ServiceKind.Notifications
        settings { server ->
            for (url in listOf("\${SERVICE_URL}/proxy", "https://\${SERVICE_HOST}/proxy", "\${pass://Kinuvo/Notifications/url}/proxy")) {
                val draft = ServiceSettings("first", mapOf("topic" to "news", "url" to url))
                server.saveService(kind, draft)
                assertEquals(url, files.serviceConfig(kind).options["url"])
                assertEquals(0, configured)
            }
            assertEquals("Sent", server.testService(TestServiceParams(kind)).summary)
            assertEquals("\${pass://Kinuvo/Notifications/url}/proxy", sentOptions["url"])
        }
    }

    @Test fun defaultUrlsStayImplicitUnlessExplicitlyConfigured() = runTest {
        val kind = ServiceKind.Notifications
        settings { server ->
            val draft = ServiceSettings("first", mapOf("topic" to "news", "url" to ""))
            server.saveService(kind, draft)
            assertFalse("url" in files.serviceConfig(kind).options)
            assertEquals("Sent", server.testService(TestServiceParams(kind)).summary)
            assertEquals("https://example.org", sentOptions["url"])
            assertFalse("url" in files.serviceConfig(kind).options)
            server.saveService(kind, draft.copy(options = draft.options + ("url" to "https://example.org")))
            assertEquals("https://example.org", files.serviceConfig(kind).options["url"])
        }
    }
}
