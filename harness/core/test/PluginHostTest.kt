package kinuvo.harness.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kinuvo.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PluginHostTest {
    private val instance = PluginInstanceId("example.plugin", "work")
    private val manifest = PluginManifest(PluginDefinition(instance.pluginId, "Example"),
        access = PluginAccess(network = setOf("server"), secrets = setOf("token")))
    private var grants = PluginGrants(instance)
    private val seen = mutableListOf<String>()
    private val expanded = mutableListOf<Pair<PluginInstanceId, String>>()
    private var redirect = false
    private var responseBody = "ok"
    private val http = HttpClient(MockEngine { request ->
        seen += request.url.toString()
        if (redirect) respond("", HttpStatusCode.Found, headersOf("Location", "https://outside.example/stolen"))
        else respond(responseBody)
    }) { followRedirects = true }
    private val hosts = PluginHosts(http) { owner, value -> expanded += owner to value; value.replace("\${TOKEN}", "secret") }
    private val host = hosts.bind(manifest, instance) { grants }

    @AfterTest fun close() { hosts.close(); http.close() }

    @Test fun defaultDenyAndDeclarationsBothApplyBeforeAccess() = runTest {
        assertFailsWith<PluginAccessDenied> { host.http(PluginHttpRequest("server")) }
        assertFailsWith<PluginAccessDenied> { host.secret("token") }
        grants = PluginGrants(instance, network = mapOf("undeclared" to "https://example.org"), secrets = mapOf("other" to "do not read"))
        assertFailsWith<PluginAccessDenied> { host.http(PluginHttpRequest("undeclared")) }
        assertFailsWith<PluginAccessDenied> { host.secret("other") }
        assertTrue(seen.isEmpty())
        assertTrue(expanded.isEmpty())
    }

    @Test fun grantsStayWithinAnInstanceAndCanBeRevoked() = runTest {
        grants = PluginGrants(instance.copy(instanceId = "personal"), secrets = mapOf("token" to "\${TOKEN}"))
        assertFailsWith<PluginAccessDenied> { host.secret("token") }
        assertTrue(expanded.isEmpty())
        grants = PluginGrants(instance, secrets = mapOf("token" to "\${TOKEN}"))
        assertEquals("secret", host.secret("token"))
        assertEquals(listOf(instance to "\${TOKEN}"), expanded)
        grants = PluginGrants(instance)
        assertFailsWith<PluginAccessDenied> { host.secret("token") }
    }

    @Test fun endpointsAreHostBoundAndRedirectsNeverFollowCredentials() = runTest {
        grants = PluginGrants(instance, network = mapOf("server" to "https://api.example/proxy"))
        redirect = true
        val response = host.http(PluginHttpRequest("server", listOf("v2", "search"), headers = mapOf("Authorization" to "Bearer token")))
        assertEquals(302, response.status)
        assertEquals(listOf("https://api.example/proxy/v2/search"), seen)
        for (request in listOf(PluginHttpRequest("server", headers = mapOf("Host" to "outside.example")),
            PluginHttpRequest("server", path = listOf("..")), PluginHttpRequest("server", method = "CONNECT"))) {
            assertFailsWith<IllegalArgumentException> { host.http(request) }
        }
        assertEquals(1, seen.size)
        grants = grants.copy(network = mapOf("server" to "file:///private/data"))
        assertFailsWith<IllegalArgumentException> { host.http(PluginHttpRequest("server")) }
    }

    @Test fun requestAndResponseSizesAreBoundedAndPathsCannotChangeTheOrigin() = runTest {
        grants = PluginGrants(instance, network = mapOf("server" to "https://api.example"))
        assertFailsWith<IllegalArgumentException> {
            host.http(PluginHttpRequest("server", body = "x".repeat(PluginHosts.MAX_BYTES + 1)))
        }
        assertTrue(seen.isEmpty())
        host.http(PluginHttpRequest("server", path = listOf("https://outside.example/stolen")))
        assertEquals("api.example", io.ktor.http.Url(seen.single()).host)
        responseBody = "x".repeat(PluginHosts.MAX_BYTES + 1)
        assertFailsWith<IllegalStateException> { host.http(PluginHttpRequest("server")) }
    }

    @Test fun revocationDuringReferenceResolutionStopsThePendingOperation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val runtime = PluginHosts(http) { _, value -> entered.complete(Unit); resume.await(); value }
        try {
            grants = PluginGrants(instance, network = mapOf("server" to "https://api.example"))
            val pending = async { runCatching { runtime.bind(manifest, instance) { grants }.http(PluginHttpRequest("server")) } }
            entered.await()
            grants = PluginGrants(instance)
            resume.complete(Unit)
            assertIs<PluginAccessDenied>(pending.await().exceptionOrNull())
            assertTrue(seen.isEmpty())
        } finally { runtime.close() }
    }
}
