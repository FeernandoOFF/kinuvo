package kinuvo.harness.server

import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.*
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.ntfy.NtfyPlugin
import kinuvo.protocol.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ServicePluginSettingsTest {
    @Test fun nativeNotificationsUseHostBoundConfigurationWithoutEagerResolution() = runBlocking {
        val received = CompletableDeferred<Pair<String?, String>>()
        val http = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing { post("/notify") {
                received.complete(call.request.headers["Authorization"] to call.receiveText())
                call.respondText("{}")
            } }
        }.start(wait = false)
        val home = Files.createTempDirectory("plugin-host-notifications").toFile()
        val files = AgentFiles(Path(home.path))
        val installation = ServerInstallation.assemble(files, plugins = listOf(NtfyPlugin()))
        try {
            val settings = installation.settings
            settings.saveService(ServiceKind.Notifications,
                ServiceSettings("ntfy", mapOf("topic" to "\${TOPIC}", "url" to "\${NOTIFY_URL}/notify")),
                mapOf("token" to SecretUpdate.Replace("\${NOTIFY_TOKEN}")))
            assertNotNull(settings.notifier())
            val port = http.engine.resolvedConnectors().single().port
            writeAtomically(files.sharedEnv, "NOTIFY_URL=http://127.0.0.1:$port\nTOPIC=host-bound-topic\nNOTIFY_TOKEN=host-bound-token\n", private = true)
            withTimeout(10_000) {
                assertEquals("Sent", settings.testService(TestServiceParams(ServiceKind.Notifications, input = "hello")).summary)
                val (token, body) = received.await()
                assertEquals("Bearer host-bound-token", token)
                assertContains(body, "host-bound-topic")
                assertContains(body, "hello")
                assertFalse(files.serviceConfig(ServiceKind.Notifications).options.values.any { "host-bound" in it })
            }
        } finally { installation.resources.close(); http.stop(0, 1000); home.deleteRecursively() }
    }

    @Test fun firstPartySettingsKeepTopicsVisibleAndDefaultsImplicit() = runTest {
        val home = Files.createTempDirectory("service-plugin-settings").toFile()
        val files = AgentFiles(Path(home.path))
        val secrets = Secrets()
        val installation = ServerInstallation.assemble(files, plugins = listOf(NtfyPlugin(), FirecrawlPlugin()), secrets = secrets)
        try {
            val server = installation.settings
            server.saveService(ServiceKind.Notifications, ServiceSettings("ntfy", mapOf("topic" to "kinuvo-subscribe")), mapOf("token" to SecretUpdate.Replace("private-token")))
            val setup = server.serviceSetup(ServiceKind.Notifications)
            assertEquals(PluginInstanceId("kinuvo.ntfy"), setup.providers.single().owner)
            assertEquals("kinuvo-subscribe", setup.settings.options["topic"])
            assertEquals(KnowledgeSettings.SECRET, setup.settings.options["token"])
            assertEquals(setOf("token"), setup.configuredSecrets)
            assertFalse(setup.providers.single().schema.fields.first { it.key == "topic" }.sensitive)
            assertNull(files.serviceConfig(ServiceKind.Notifications).options["url"])

            server.saveService(ServiceKind.Search, ServiceSettings("firecrawl"), mapOf("apiKey" to SecretUpdate.Replace("private-key")))
            assertNull(files.serviceConfig(ServiceKind.Search).options["url"])
            val search = server.serviceSetup(ServiceKind.Search)
            assertEquals(KnowledgeSettings.SECRET, search.settings.options["apiKey"])
            assertEquals(setOf("apiKey"), search.configuredSecrets)
        } finally { installation.resources.close(); home.deleteRecursively() }
    }
}
