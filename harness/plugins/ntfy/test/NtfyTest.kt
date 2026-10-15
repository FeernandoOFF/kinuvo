package kinuvo.harness.ntfy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kinuvo.harness.core.Notification
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kinuvo.harness.core.*
import kinuvo.protocol.*

class NtfyTest {
    private val hosts = mutableListOf<PluginHosts>()
    private val requests = mutableListOf<Triple<String, String?, String>>()
    private var status = HttpStatusCode.OK
    private val http = HttpClient(MockEngine { req ->
        requests += Triple(req.url.toString(), req.headers[HttpHeaders.Authorization], (req.body as TextContent).text)
        respond("{}", status)
    })
    private val expand: suspend (String) -> String = { if (it == "\${pass://Kinuvo/ntfy/token}") "tk_1" else it }
    private val ntfy = ntfy(mapOf("topic" to "kinuvo-x7"), expand)

    @AfterTest fun close() { hosts.forEach { it.close() }; http.close() }

    private fun ntfy(options: Map<String, String>, expand: suspend (String) -> String = { it }): Notifier {
        val plugin = NtfyPlugin()
        val provider = plugin.notifications.single()
        provider.checkOptions(options)
        val effective = provider.effectiveOptions(options)
        val runtime = PluginHosts(http) { _, value -> expand(value) }.also { hosts += it }
        val grants = PluginGrants(plugin.instance,
            network = effective.filterKeys { it in provider.hostAccess.network },
            secrets = effective.filterKeys { it in provider.hostAccess.secrets })
        return provider.configured(effective, runtime.bind(plugin.manifest, plugin.instance) { grants })
    }

    @Test
    fun publishesToTheTopic() = runTest {
        ntfy.send(Notification("Kai needs an Approval", "shell: rm -rf build", "kai", "20261005-1200-ab12", urgent = true))
        assertEquals(Triple("https://ntfy.sh", null, """{"topic":"kinuvo-x7","title":"Kai needs an Approval","message":"shell: rm -rf build","priority":4,""" +
            """"click":"kinuvo://chat/kai/20261005-1200-ab12","actions":[{"action":"view","label":"Open in Kinuvo","url":"kinuvo://chat/kai/20261005-1200-ab12"}]}"""), requests.last())

        ntfy(mapOf("topic" to "kinuvo-x7", "url" to "https://ntfy.home/", "token" to "\${pass://Kinuvo/ntfy/token}"), expand).send(Notification("Kai replied", ""))
        assertEquals("https://ntfy.home" to "Bearer tk_1", requests.last().let { it.first to it.second })
        assertContains(requests.last().third, """"message":"Kai replied","priority":3""")
    }

    @Test
    fun reportsFailuresAndMissingTopics() = runTest {
        status = HttpStatusCode.Forbidden
        assertFailsWith<IllegalStateException> { ntfy.send(Notification("t", "b")) }
        assertFailsWith<IllegalArgumentException> { ntfy(emptyMap()) }
    }

    @Test fun validatesInterpolatedUrlsBeforeSendingCredentials() = runTest {
        val options = mapOf("topic" to "kinuvo-test", "token" to "secret", "url" to "\${NOTIFY_URL}/proxy")
        ntfy(options) { it.replace("\${NOTIFY_URL}", "https://notify.example.org") }.send(Notification("Test", ""))
        assertEquals("https://notify.example.org/proxy", requests.single().first)
        assertFailsWith<IllegalArgumentException> {
            ntfy(options) { it.replace("\${NOTIFY_URL}", "file:///tmp") }.send(Notification("Test", ""))
        }
        assertEquals(1, requests.size)
    }
}
