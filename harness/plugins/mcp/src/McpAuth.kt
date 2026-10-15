package kinuvo.harness.mcp

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.collections.ConcurrentMutableSet
import kotlin.io.encoding.Base64
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import kinuvo.protocol.McpAuthStatus
import kinuvo.protocol.McpParams
import kinuvo.protocol.McpRedirect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** An HTTP MCP server refused Kinuvo until the user signs in. */
class McpAuthRequired(val server: String, scope: String? = null) : Exception(
    "MCP server '$server' needs ${scope?.let { "more access ($it); sign in again" } ?: "sign-in"}: run `kinuvo mcp login $server` or sign in from Settings → MCP servers"
)

/**
 * OAuth sign-in to HTTP MCP servers, per the MCP authorization spec: discovery from the server's 401, dynamic client
 * registration (or a configured client), authorization code with PKCE, and refresh. The browser runs on a Device, which
 * hands back the URL it was sent to; the Server keeps the verifier and the tokens.
 */
class McpAuth(
    val signIns: McpSignInStore,
    private val http: HttpClient = HttpClient {
        // Discovered URLs are checked before use; a redirect would skip that.
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
    private val now: () -> Long = { kotlin.time.Clock.System.now().epochSeconds },
) : AutoCloseable {
    /** [secretPost] sends the secret in the form (`client_secret_post`) instead of Basic auth. */
    private class Client(val id: String, val secret: String?, val secretPost: Boolean = false)

    private class Pending(
        val server: McpServerConfig.Http,
        val owner: McpParams,
        val redirect: String,
        val verifier: String,
        val tokenEndpoint: String,
        val resource: String,
        val client: Client,
        val scope: String?,
        val expires: Long,
    )

    private val pending = ConcurrentMutableMap<String, Pending>()
    private val required = ConcurrentMutableSet<String>()
    /** Scopes a server said it needs beyond the sign-in's (`insufficient_scope`), asked for at the next sign-in. */
    private val wanted = ConcurrentMutableMap<String, String>()
    private val notified = ConcurrentMutableSet<String>()
    private val generations = ConcurrentMutableMap<String, Int>()
    /** Held while a sign-in is written, so a refresh can't undo a sign-out or a newer sign-in. */
    private val writing = Mutex()

    /** Told once per server when a Routine run finds it needs sign-in, until it's signed in. */
    var onSignInNeeded: (suspend (agentId: String, server: String) -> Unit)? = null

    /** Changes with every sign-in and sign-out, so connections made with older tokens are replaced. */
    fun generation(id: String) = generations[id] ?: 0

    fun markRequired(id: String) {
        required += id
    }

    fun isRequired(id: String) = id in required

    fun wantedScope(id: String): String? = wanted[id]

    /** Notes a 403's `insufficient_scope` challenge among its `WWW-Authenticate` [headers]; true when it was one. */
    fun forbidden(id: String, headers: List<String>): Boolean {
        val challenge = bearers(headers).firstOrNull { it.parameter("error") == "insufficient_scope" } ?: return false
        challenge.parameter("scope")?.let { scope -> wanted.block { m -> m[id] = m[id]?.let { union(it, scope) } ?: scope } }
        required += id
        return true
    }

    suspend fun signInNeeded(agentId: String, server: McpServerConfig.Http) {
        if (notified.add(server.signInId)) onSignInNeeded?.invoke(agentId, server.name)
    }

    suspend fun status(signInId: String): McpAuthStatus = when {
        signInId in required -> McpAuthStatus.Required
        signIns.get(signInId) != null -> McpAuthStatus.SignedIn
        else -> McpAuthStatus.None
    }

    /** The access token for [server], refreshed when it's about to expire; null when not signed in. */
    suspend fun accessToken(server: McpServerConfig.Http): String? {
        val s = signIn(server) ?: return null
        return if (expiring(s) && s.refreshToken != null) refresh(server, null) else s.accessToken
    }

    /** New tokens for [server], unless someone refreshed since [stale] was used; null when it can't refresh. */
    suspend fun refresh(server: McpServerConfig.Http, stale: String?): String? = writing.withLock {
        val s = signIn(server) ?: return null
        if (stale != null && s.accessToken != stale) return s.accessToken
        val refreshToken = s.refreshToken ?: return null
        val res = tokenRequest(trusted(s.tokenEndpoint, s.resource), Client(s.clientId, s.clientSecret, s.secretPost)) {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("resource", s.oauthResource ?: s.resource)
        }
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) {
            if (runCatching { Json.parseToJsonElement(body).jsonObject.str("error") }.getOrNull() == "invalid_grant") signIns.remove(server.signInId)
            return null
        }
        val tokens = Json.parseToJsonElement(body).jsonObject
        val updated = s.copy(
            accessToken = tokens.str("access_token") ?: return null,
            refreshToken = tokens.str("refresh_token") ?: s.refreshToken,
            expiresAt = tokens.expiresAt(),
        )
        signIns.put(server.signInId, updated)
        updated.accessToken
    }

    /** Starts signing in to [server] for [owner]; returns the URL to open in a browser that returns to [redirect]. */
    suspend fun begin(server: McpServerConfig.Http, redirect: McpRedirect, owner: McpParams): String {
        pending.block { m -> m.values.removeAll { it.expires < now() } }
        val trusted = { url: String -> trusted(url, server.url) }
        val challenge = challenge(server.url)
        val resourceMetadata = challenge?.parameter("resource_metadata")?.let { getJson(trusted(it)) } ?: wellKnown(server.url, "oauth-protected-resource")
        // Same origin and a path prefix rather than RFC 9728's exact match: servers commonly name their origin.
        val resource = resourceMetadata?.let { m ->
            (m.str("resource") ?: error("The server's resource metadata has no resource")).also { check(covers(it, server.url)) { "The server's metadata is for $it, not ${server.url}" } }
        } ?: server.url
        val issuer = trusted(resourceMetadata?.strings("authorization_servers")?.firstOrNull() ?: origin(server.url))
        val metadata = authServerMetadata(issuer) ?: error("Couldn't find the OAuth settings of $issuer")
        check(metadata.str("issuer") == issuer) { "$issuer's OAuth settings name another issuer" }
        check(metadata.strings("code_challenge_methods_supported").orEmpty().contains("S256")) { "$issuer doesn't support PKCE, which sign-in needs" }
        val authorize = trusted(metadata.str("authorization_endpoint") ?: error("$issuer has no authorization endpoint"))
        val token = trusted(metadata.str("token_endpoint") ?: error("$issuer has no token endpoint"))
        val client = server.oauth?.let { Client(it.clientId, it.clientSecret, it.clientSecret != null && postsSecret(metadata.strings("token_endpoint_auth_methods_supported"))) }
            ?: register(metadata.str("registration_endpoint")?.let(trusted), issuer, redirect)
        val asked = server.oauth?.scopes?.takeIf { it.isNotEmpty() }?.joinToString(" ")
            ?: challenge?.parameter("scope")
            ?: resourceMetadata?.strings("scopes_supported")?.joinToString(" ")?.ifEmpty { null }
        val scope = listOfNotNull(asked, signIn(server)?.scope, wanted[server.signInId]).reduceOrNull(::union)
        val verifier = random(32)
        val state = random(16)
        val url = URLBuilder(authorize).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", client.id)
            parameters.append("redirect_uri", redirect.uri)
            parameters.append("code_challenge", base64Url(sha256(verifier.encodeToByteArray())))
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            scope?.let { parameters.append("scope", it) }
            parameters.append("resource", resource)
        }.buildString()
        pending[state] = Pending(server, owner, redirect.uri, verifier, token, resource, client, scope, now() + LOGIN_TIMEOUT)
        return url
    }

    /** Finishes the sign-in the browser returned from at [callbackUrl]; returns whose server it was. */
    suspend fun complete(callbackUrl: String): McpParams {
        val query = parseQueryString(callbackUrl.trim().substringAfter('?', "").substringBefore('#'))
        val state = query["state"] ?: error("That isn't the URL the sign-in returned to (it has no state)")
        // Together, so a sign-out either drops this flow or bumps the generation it's checked against.
        val (p, generation) = writing.withLock { pending.remove(state)?.let { it to generation(it.server.signInId) } }
            ?.takeIf { it.first.expires >= now() } ?: error("This sign-in expired or wasn't started here; start it again")
        query["error"]?.let { error("Sign-in failed: ${query["error_description"] ?: it}") }
        val code = query["code"] ?: error("The sign-in returned no code")
        val res = tokenRequest(p.tokenEndpoint, p.client) {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", p.redirect)
            append("code_verifier", p.verifier)
            append("resource", p.resource)
        }
        val body = res.bodyAsText()
        check(res.status.isSuccess()) { "Token exchange failed (${res.status.value}): ${body.take(300)}" }
        val tokens = Json.parseToJsonElement(body).jsonObject
        val id = p.server.signInId
        val signIn = McpSignIn(
            resource = p.server.url,
            tokenEndpoint = p.tokenEndpoint,
            clientId = p.client.id,
            clientSecret = p.client.secret,
            accessToken = tokens.str("access_token") ?: error("The token response has no access token"),
            refreshToken = tokens.str("refresh_token"),
            expiresAt = tokens.expiresAt(),
            scope = tokens.str("scope") ?: p.scope,
            oauthResource = p.resource,
            secretPost = p.client.secretPost,
        )
        writing.withLock {
            check(generation(id) == generation) { "Signed out of '${p.server.name}' while signing in; start again" }
            signIns.put(id, signIn)
            changed(id)
        }
        return p.owner
    }

    suspend fun logout(signInId: String) = writing.withLock {
        signIns.remove(signInId)
        pending.block { m -> m.values.removeAll { it.server.signInId == signInId } }
        changed(signInId)
    }

    override fun close() = http.close()

    private fun changed(id: String) {
        required -= id
        wanted -= id
        notified -= id
        generations.block { m -> m[id] = (m[id] ?: 0) + 1 }
    }

    private suspend fun signIn(server: McpServerConfig.Http) = signIns.get(server.signInId)?.takeIf { it.resource == server.url }

    private fun expiring(s: McpSignIn) = s.expiresAt != null && s.expiresAt - now() < 60

    private suspend fun tokenRequest(endpoint: String, client: Client, params: io.ktor.http.ParametersBuilder.() -> Unit): HttpResponse =
        http.submitForm(endpoint, parameters {
            params()
            if (client.secret == null || client.secretPost) append("client_id", client.id)
            if (client.secretPost) client.secret?.let { append("client_secret", it) }
        }) { if (!client.secretPost) client.secret?.let { basicAuth(client.id, it) } }

    /** The server's `WWW-Authenticate` challenge to an unauthenticated initialize, if it sent one. */
    private suspend fun challenge(url: String): HttpAuthHeader.Parameterized? = quietly {
        val res = http.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"kinuvo","version":"0.1"}}}""")
        }
        res.takeIf { it.status == HttpStatusCode.Unauthorized }?.headers?.getAll(HttpHeaders.WWWAuthenticate)?.let(::bearer)
    }

    /** RFC 9728/8414 metadata at `/.well-known/<name>` inserted before the URL's path, then at the root. */
    private suspend fun wellKnown(url: String, name: String): JsonObject? {
        val u = Url(url)
        val path = u.encodedPath.trimEnd('/')
        return listOfNotNull("/.well-known/$name$path".takeIf { path.isNotEmpty() }, "/.well-known/$name")
            .firstNotNullOfOrNull { getJson(origin(url) + it) }
    }

    /** RFC 8414 then OIDC discovery, in the MCP spec's order for issuers with a path. */
    private suspend fun authServerMetadata(issuer: String): JsonObject? {
        val o = origin(issuer)
        val path = Url(issuer).encodedPath.trimEnd('/')
        val urls = if (path.isEmpty()) listOf("$o/.well-known/oauth-authorization-server", "$o/.well-known/openid-configuration")
        else listOf("$o/.well-known/oauth-authorization-server$path", "$o/.well-known/openid-configuration$path", "$o$path/.well-known/openid-configuration")
        return urls.firstNotNullOfOrNull { getJson(it) }
    }

    private suspend fun register(endpoint: String?, issuer: String, redirect: McpRedirect): Client {
        endpoint ?: error("$issuer doesn't allow dynamic client registration; set a client ID for this server")
        suspend fun post(redirects: List<String>) = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("client_name", "Kinuvo")
                put("redirect_uris", JsonArray(redirects.map(::JsonPrimitive)))
                put("grant_types", JsonArray(listOf("authorization_code", "refresh_token").map(::JsonPrimitive)))
                put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                put("token_endpoint_auth_method", "none")
            }.toString())
        }
        // Both redirects let one registration serve every Device; some servers refuse the app scheme.
        val res = post(McpRedirect.entries.map { it.uri }).takeIf { it.status.isSuccess() } ?: post(listOf(redirect.uri))
        val body = res.bodyAsText()
        check(res.status.isSuccess()) { "Client registration at $issuer failed (${res.status.value}): ${body.take(300)}" }
        val o = Json.parseToJsonElement(body).jsonObject
        return Client(o.str("client_id") ?: error("Client registration returned no client_id"), o.str("client_secret"), o.str("token_endpoint_auth_method") == "client_secret_post")
    }

    private suspend fun getJson(url: String): JsonObject? = quietly {
        val res = http.get(url)
        if (res.status.isSuccess()) Json.parseToJsonElement(res.bodyAsText()) as? JsonObject else null
    }

    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.expiresAt() = (get("expires_in") as? JsonPrimitive)?.longOrNull?.let { now() + it }

    private companion object {
        const val LOGIN_TIMEOUT = 10 * 60L

        /** The Bearer challenge among [values] that points to resource metadata, else the first. */
        fun bearer(values: List<String>): HttpAuthHeader.Parameterized? = bearers(values).let { all ->
            all.firstOrNull { it.parameter("resource_metadata") != null } ?: all.firstOrNull()
        }

        /** Every Bearer challenge among [values]; a response may send several. */
        fun bearers(values: List<String>) = values.flatMap(::challenges).filter { it.authScheme.equals("Bearer", ignoreCase = true) }

        /** Splits a header like `Basic realm="x", Bearer scope="a"` at the commas that start a new challenge. */
        fun challenges(header: String): List<HttpAuthHeader.Parameterized> {
            val parts = mutableListOf(StringBuilder())
            var quoted = false
            var escaped = false
            header.forEachIndexed { i, c ->
                when {
                    escaped -> escaped = false
                    quoted && c == '\\' -> escaped = true
                    c == '"' -> quoted = !quoted
                }
                if (c == ',' && !quoted && CHALLENGE.matchAt(header, i + 1) != null) parts += StringBuilder() else parts.last().append(c)
            }
            return parts.map { it.toString().trim() }.mapNotNull { part ->
                if (BARE.matches(part)) HttpAuthHeader.Parameterized(part, emptyMap<String, String>())
                else runCatching { parseAuthorizationHeader(part) as? HttpAuthHeader.Parameterized }.getOrNull()
            }
        }

        const val TOKEN = """[A-Za-z][\w!#$%&'*+.^`|~-]*"""

        /** A scheme followed by its first parameter (not `name=value`), or alone. */
        val CHALLENGE = Regex("""\s*$TOKEN(\s+[^=\s,]|\s*(,|$))""")
        val BARE = Regex(TOKEN)

        /**
         * [url] when it's HTTPS, on this machine, or on the MCP server's own origin; codes, verifiers and tokens never
         * go over plain HTTP to anywhere else.
         */
        fun trusted(url: String, server: String): String {
            val u = Url(url)
            check(u.protocol.name == "https" || u.host.lowercase() in LOOPBACK || origin(url) == origin(server)) { "Refusing $url: sign-in needs HTTPS" }
            return url
        }

        /** Whether the protected resource [resource] (RFC 9728) covers the MCP server at [url]: same origin, a path prefix. */
        fun covers(resource: String, url: String): Boolean {
            if (origin(resource) != origin(url)) return false
            val path = Url(resource).encodedPath.trimEnd('/')
            val target = Url(url).encodedPath.trimEnd('/')
            return path.isEmpty() || target == path || target.startsWith("$path/")
        }

        /** Basic auth unless the server only takes the secret in the form. */
        fun postsSecret(methods: List<String>?) = methods != null && "client_secret_basic" !in methods && "client_secret_post" in methods

        val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        fun union(a: String, b: String) = (a.split(' ') + b.split(' ')).filter { it.isNotBlank() }.distinct().joinToString(" ")
        fun random(bytes: Int) = base64Url(secureRandomBytes(bytes))
        fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
        fun origin(url: String) = Url(url).let { u -> URLBuilder(protocol = u.protocol, host = u.host.lowercase(), port = u.port).buildString().trimEnd('/') }
        fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}
