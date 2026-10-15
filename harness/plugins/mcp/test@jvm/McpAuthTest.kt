package kinuvo.harness.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kinuvo.harness.core.Keychain
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.McpAuthStatus
import kinuvo.protocol.McpParams
import kinuvo.protocol.McpRedirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpAuthTest {
    private val dir = Files.createTempDirectory("mcp-auth").toFile()
    private val keychain = FakeKeychain()
    private val auth = McpAuth(McpSignIns(dir, keychain))
    private val fake = FakeOAuthServer()
    private val server = McpServerConfig.Http("linear", "${fake.base}/mcp", signInId = "shared/linear")
    private val owner = McpParams("default", "linear", ConfigScope.Shared)

    @AfterTest
    fun stop() = fake.stop()

    @Test
    fun signsInWithDiscoveryRegistrationAndPkce() = runBlocking {
        val url = Url(auth.begin(server, McpRedirect.Loopback, owner))
        val q = url.parameters
        assertEquals("${fake.base}/authorize", url.toString().substringBefore('?'))
        assertEquals("client-1", q["client_id"])
        assertEquals(McpRedirect.Loopback.uri, q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("read write", q["scope"])
        assertEquals("${fake.base}/mcp", q["resource"])
        assertEquals(McpRedirect.entries.map { it.uri }, fake.registeredRedirects)
        fake.challenge = q["code_challenge"]!!

        assertEquals(owner, auth.complete("${McpRedirect.Loopback.uri}?code=good&state=${q["state"]}"))

        assertEquals("access-1", auth.accessToken(server))
        assertEquals(McpAuthStatus.SignedIn, auth.status(server.signInId))
        assertEquals(1, auth.generation(server.signInId))
        assertFailsWith<IllegalStateException> { auth.complete("${McpRedirect.Loopback.uri}?code=good&state=${q["state"]}") }

        assertEquals("access-2", auth.refresh(server, "access-1"))
        assertEquals("refresh-1", auth.signIns.get(server.signInId)!!.refreshToken)
        assertEquals("access-2", auth.refresh(server, "access-1"), "a stale token doesn't refresh twice")
        assertEquals(1, fake.refreshes)

        auth.logout(server.signInId)
        assertNull(auth.accessToken(server))
        assertEquals(McpAuthStatus.None, auth.status(server.signInId))
    }

    @Test
    fun asksForMoreScopeWhenTheServerSaysItIsInsufficient() = runBlocking {
        val first = Url(auth.begin(server, McpRedirect.Loopback, owner)).parameters
        fake.challenge = first["code_challenge"]
        auth.complete("${McpRedirect.Loopback.uri}?code=good&state=${first["state"]}")
        fake.forbidden = true

        val cfg = File(dir, "mcp.json").apply { writeText("""{"mcpServers": {"linear": {"type": "http", "url": "${fake.base}/mcp"}}}""") }
        val manager = McpManager(listOf(Path(cfg.path)), auth = auth, signInScopes = listOf("shared"))
        val warning = manager.tools().second.single()
        manager.close()
        assertTrue("more access (admin)" in warning, warning)
        assertEquals(McpAuthStatus.Required, auth.status(server.signInId))
        assertEquals("read write admin", Url(auth.begin(server, McpRedirect.Loopback, owner)).parameters["scope"])
    }

    @Test
    fun refusesMetadataThatCouldLeakTokensOrSkipPkce() = runBlocking {
        fake.resource = "https://victim.example/mcp"
        assertTrue("not ${server.url}" in assertFailsWith<IllegalStateException> { auth.begin(server, McpRedirect.Loopback, owner) }.message!!)
        fake.resource = "${fake.base}/mcp"
        fake.authServer = "http://auth.example"
        assertTrue("needs HTTPS" in assertFailsWith<IllegalStateException> { auth.begin(server, McpRedirect.Loopback, owner) }.message!!)
        fake.authServer = fake.base
        fake.pkce = false
        assertTrue("PKCE" in assertFailsWith<IllegalStateException> { auth.begin(server, McpRedirect.Loopback, owner) }.message!!)
    }

    @Test
    fun signOutDropsSignInsStillInTheBrowser() = runBlocking {
        val state = Url(auth.begin(server, McpRedirect.Loopback, owner)).parameters["state"]
        auth.logout(server.signInId)
        assertFailsWith<IllegalStateException> { auth.complete("${McpRedirect.Loopback.uri}?code=good&state=$state") }
        Unit
    }

    @Test
    fun signOutDuringTheTokenExchangeWins() = runBlocking {
        val q = Url(auth.begin(server, McpRedirect.Loopback, owner)).parameters
        fake.challenge = q["code_challenge"]
        fake.gate = CountDownLatch(1)
        val completing = async(Dispatchers.IO) { runCatching { auth.complete("${McpRedirect.Loopback.uri}?code=good&state=${q["state"]}") } }
        fake.exchanging.await()
        auth.logout(server.signInId)
        fake.gate!!.countDown()
        assertTrue(completing.await().isFailure)
        assertEquals(McpAuthStatus.None, auth.status(server.signInId))
    }

    @Test
    fun findsBearerAmongChallenges() {
        assertTrue(auth.forbidden("a", listOf("""Basic realm="path\\\\", Bearer error="insufficient_scope", scope="admin"""")))
        assertEquals("admin", auth.wantedScope("a"))
        assertTrue(auth.forbidden("b", listOf("Basic realm=x", """Bearer error="insufficient_scope", scope="read"""")))
        assertEquals("read", auth.wantedScope("b"))
        assertTrue(auth.forbidden("d", listOf("Bearer realm=default", """Bearer realm=admin, error="insufficient_scope", scope="admin"""")))
        assertFalse(auth.forbidden("c", listOf("""Basic realm="a, Bearer error=insufficient_scope"""")))
    }

    @Test
    fun reportsDeniedSignIn() = runBlocking {
        val state = Url(auth.begin(server, McpRedirect.App, owner)).parameters["state"]
        val e = assertFailsWith<IllegalStateException> { auth.complete("${McpRedirect.App.uri}?error=access_denied&error_description=No+thanks&state=$state") }
        assertEquals("Sign-in failed: No thanks", e.message)
    }

    @Test
    fun signInsAreEncryptedWithTheKeychainKeyElsePlainForTheOwner() = runBlocking<Unit> {
        val signIn = McpSignIn("https://x/mcp", "https://x/token", "c", accessToken = "secret-token")
        auth.signIns.put("agents/a/x", signIn)
        val encrypted = File(dir, "agents/a/x.enc")
        assertTrue(encrypted.exists() && "secret-token" !in encrypted.readText())
        assertEquals(signIn, McpSignIns(dir, keychain).get("agents/a/x"))

        val plainDir = Files.createTempDirectory("mcp-auth").toFile()
        McpSignIns(plainDir, null).put("shared/x", signIn)
        val plain = File(plainDir, "shared/x.json")
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(plain.toPath())))
        assertEquals(signIn, McpSignIns(plainDir, null).get("shared/x"))

        auth.signIns.removeAll("agents/a")
        assertFalse(encrypted.exists())
        assertFailsWith<IllegalArgumentException> { auth.signIns.get("../x") }
    }

    @Test
    fun serverRefusingKinuvoNeedsSignIn() = runBlocking {
        val cfg = File(dir, "mcp.json").apply { writeText("""{"mcpServers": {"linear": {"type": "http", "url": "${fake.base}/mcp"}}}""") }
        val manager = McpManager(listOf(Path(cfg.path)), auth = auth, signInScopes = listOf("shared"))
        val (tools, warnings) = manager.tools()
        assertEquals(listOf("linear"), manager.needingSignIn().map { it.name })
        manager.close()
        assertTrue(tools.isEmpty())
        assertTrue("needs sign-in" in warnings.single(), warnings.single())
        assertEquals(McpAuthStatus.Required, auth.status("shared/linear"))
    }

    private class FakeKeychain : Keychain {
        var value: String? = null
        override val name = "Fake Keychain"
        override suspend fun read() = value
        override suspend fun write(token: String) { value = token }
        override suspend fun delete() { value = null }
    }

    /** An MCP server that refuses unauthenticated requests, with its own authorization server. */
    private class FakeOAuthServer {
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${http.address.port}"
        var registeredRedirects: List<String> = emptyList()
        var challenge: String? = null
        var refreshes = 0
        var forbidden = false
        var resource = "$base/mcp"
        var authServer = base
        var pkce = true
        /** When set, the token endpoint waits for it, after counting down [exchanging]. */
        var gate: CountDownLatch? = null
        val exchanging = CountDownLatch(1)

        init {
            route("/mcp") { if (forbidden && it.requestHeaders.getFirst("Authorization") == "Bearer access-1") it.respond(403, "", "WWW-Authenticate" to """Bearer error="insufficient_scope", scope="admin"""")
                else it.respond(401, "", "WWW-Authenticate" to """Basic realm="old, api", Bearer resource_metadata="$base/.well-known/oauth-protected-resource/mcp", scope="read write"""") }
            route("/.well-known/oauth-protected-resource/mcp") { it.json("""{"resource":"$resource","authorization_servers":["$authServer"]}""") }
            route("/.well-known/oauth-authorization-server") {
                val methods = if (pkce) ""","code_challenge_methods_supported":["S256"]""" else ""
                it.json("""{"issuer":"$base","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"$methods}""")
            }
            route("/register") { ex ->
                val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
                registeredRedirects = body.getValue("redirect_uris").jsonArray.map { it.jsonPrimitive.content }
                ex.json("""{"client_id":"client-1"}""", 201)
            }
            route("/token") { ex ->
                val form = parseQueryString(ex.requestBody.readBytes().decodeToString())
                when (form["grant_type"]) {
                    "authorization_code" -> {
                        exchanging.countDown()
                        gate?.await()
                        val verified = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray())) == challenge
                        if (form["code"] == "good" && verified && form["client_id"] == "client-1") ex.json("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}""")
                        else ex.json("""{"error":"invalid_grant"}""", 400)
                    }
                    "refresh_token" -> { refreshes++; ex.json("""{"access_token":"access-2","expires_in":3600}""") }
                    else -> ex.json("""{"error":"unsupported_grant_type"}""", 400)
                }
            }
            http.start()
        }

        fun stop() = http.stop(0)

        private fun route(path: String, handle: (HttpExchange) -> Unit) {
            http.createContext(path) { ex -> if (ex.requestURI.path == path) handle(ex) else ex.respond(404, "") }
        }

        private fun HttpExchange.json(body: String, status: Int = 200) = respond(status, body, "Content-Type" to "application/json")

        private fun HttpExchange.respond(status: Int, body: String, vararg headers: Pair<String, String>) {
            headers.forEach { (k, v) -> responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
