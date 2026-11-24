package kinuvo.plugins.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * A lazily connected MCP server; reconnects on the next use after a failure. HTTP servers sign in through [auth].
 * A failed connection attempt is capped at [connectTimeoutMillis] and, once failed, short-circuits for
 * [backoffSeconds] instead of reconnecting (and re-waiting) on every call, so a server needing sign-in or otherwise
 * unreachable never delays a run by more than one connect attempt.
 */
class McpServer(
    val config: McpServerConfig,
    private val auth: McpAuth? = null,
    private val connectTimeoutMillis: Long = 8_000,
    private val backoffSeconds: Long = 30,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    /** The sign-in generation its tokens came from; see [McpAuth.generation]. */
    val authGeneration = (config as? McpServerConfig.Http)?.let { auth?.generation(it.signInId) } ?: 0

    private val mutex = Mutex()
    private var client: Client? = null
    private var process: StdioProcess? = null
    private var cooldownUntil: Long? = null
    private var lastFailure: Throwable? = null

    suspend fun listTools(): List<Tool> = withClient { it.listTools().tools }

    suspend fun callTool(name: String, args: JsonObject): CallToolResult = withClient { it.callTool(name, args) }

    suspend fun readResource(uri: String): ReadResourceResult = withClient { it.readResource(ReadResourceRequest(ReadResourceRequestParams(uri))) }

    private suspend fun <T> withClient(block: suspend (Client) -> T): T {
        cooldownUntil?.let { until -> if (now() < until) throw lastFailure!! }
        return try {
            val result = block(mutex.withLock { client ?: connect().also { client = it } })
            cooldownUntil = null
            lastFailure = null
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val detail = processDetail()
            close()
            val http = config as? McpServerConfig.Http
            val failure = if (http != null && auth != null && (unauthorized(e) || auth.isRequired(http.signInId) && forbidden(e))) {
                auth.markRequired(http.signInId)
                McpAuthRequired(http.name, auth.wantedScope(http.signInId))
            } else if (detail != null) IllegalStateException("${e.message} — $detail", e) else e
            cooldownUntil = now() + backoffSeconds
            lastFailure = failure
            throw failure
        }
    }

    /** Last stderr lines and exit code of a stdio server, to explain a failure. */
    private fun processDetail(): String? = process?.detail()

    /** Counts connections, so what was read from an earlier one is read again after a reconnect. */
    var connections = 0
        private set

    private suspend fun connect(): Client = withTimeoutOrNull(connectTimeoutMillis) {
        val c = Client(Implementation("kinuvo", "0.1"), ClientOptions(capabilities = appCapabilities))
        c.connect(transport())
        connections++
        c
    } ?: throw IllegalStateException("MCP server '${config.name}' didn't respond within ${connectTimeoutMillis}ms")

    private fun transport(): Transport = when (val cfg = config) {
        is McpServerConfig.Stdio -> startStdio(cfg).also { process = it }.transport
        is McpServerConfig.Http -> StreamableHttpClientTransport(
            client = HttpClient {
                install(SSE)
                val auth = auth
                if (auth != null && cfg.headers.keys.none { it.equals("Authorization", ignoreCase = true) }) install(Auth) {
                    bearer {
                        loadTokens { auth.accessToken(cfg)?.let { BearerTokens(it, null) } }
                        refreshTokens { auth.refresh(cfg, oldTokens?.accessToken)?.let { BearerTokens(it, null) } }
                        sendWithoutRequest { true }
                    }
                }
                if (auth != null) install(createClientPlugin("McpStepUp") {
                    onResponse { if (it.status == HttpStatusCode.Forbidden) auth.forbidden(cfg.signInId, it.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty()) }
                })
            },
            url = cfg.url,
            requestBuilder = { cfg.headers.forEach { (k, v) -> header(k, v) } },
        )
    }

    suspend fun close() {
        val c = client
        client = null
        runCatching { c?.close() }
        process?.destroy()
        process = null
    }

    private fun unauthorized(e: Throwable) = status(e, HttpStatusCode.Unauthorized) || generateSequence(e) { it.cause }.any { it is McpAuthRequired }

    private fun forbidden(e: Throwable) = status(e, HttpStatusCode.Forbidden)

    private fun status(e: Throwable, status: HttpStatusCode) = generateSequence(e) { it.cause }.any {
        (it is StreamableHttpError && it.code == status.value) || (it is SSEClientException && it.response?.status == status)
    }

    fun destroyNow() {
        process?.destroyNow()
    }
}

internal val appCapabilities = ClientCapabilities(extensions = mapOf(
    "io.modelcontextprotocol/ui" to buildJsonObject {
        put("mimeTypes", buildJsonArray { add("text/html;profile=mcp-app") })
    },
))
