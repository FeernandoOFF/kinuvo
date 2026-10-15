package kinuvo.harness.mcp

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
import kotlinx.serialization.json.JsonObject

/** A lazily connected MCP server; reconnects on the next use after a failure. HTTP servers sign in through [auth]. */
class McpServer(val config: McpServerConfig, private val auth: McpAuth? = null) {
    /** The sign-in generation its tokens came from; see [McpAuth.generation]. */
    val authGeneration = (config as? McpServerConfig.Http)?.let { auth?.generation(it.signInId) } ?: 0

    private val mutex = Mutex()
    private var client: Client? = null
    private var process: StdioProcess? = null

    suspend fun listTools(): List<Tool> = withClient { it.listTools().tools }

    suspend fun callTool(name: String, args: JsonObject): CallToolResult = withClient { it.callTool(name, args) }

    private suspend fun <T> withClient(block: suspend (Client) -> T): T = try {
        block(mutex.withLock { client ?: connect().also { client = it } })
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val detail = processDetail()
        close()
        val http = config as? McpServerConfig.Http
        if (http != null && auth != null && (unauthorized(e) || auth.isRequired(http.signInId) && forbidden(e))) {
            auth.markRequired(http.signInId)
            throw McpAuthRequired(http.name, auth.wantedScope(http.signInId))
        }
        if (detail == null) throw e
        throw IllegalStateException("${e.message} — $detail", e)
    }

    /** Last stderr lines and exit code of a stdio server, to explain a failure. */
    private fun processDetail(): String? = process?.detail()

    private suspend fun connect(): Client {
        val c = Client(Implementation("kinuvo", "0.1"))
        c.connect(transport())
        return c
    }

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
