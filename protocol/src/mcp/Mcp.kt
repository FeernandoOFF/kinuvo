package kinuvo.protocol.mcp

import kinuvo.protocol.agent.ConfigScope
import kotlinx.serialization.Serializable

/** One `mcpServers` entry: [command] for stdio servers, [url] for HTTP ones. Values may hold `${VAR}` references. */
@Serializable
data class McpServerInfo(
    val name: String,
    val scope: ConfigScope = ConfigScope.Agent,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val includeTools: List<String>? = null,
    val excludeTools: List<String> = emptyList(),
    /** "auto" or "ask" overrides the tools' read-only hints. */
    val approval: String? = null,
    /** A pre-registered OAuth client, for HTTP servers whose sign-in doesn't allow dynamic registration. */
    val oauth: McpOAuthClient? = null,
    /** False when an Agent-scoped server of the same name replaces this shared one. */
    val active: Boolean = true,
    /** Read-only: whether the server is signed in through OAuth or needs it. */
    val auth: McpAuthStatus = McpAuthStatus.None,
    /** For a shared server, the Agents that use it; null for every Agent. */
    val agents: List<String>? = null,
)

/** [clientSecret] and [scopes] are optional; values may hold `${VAR}` references. */
@Serializable
data class McpOAuthClient(val clientId: String, val clientSecret: String? = null, val scopes: List<String> = emptyList())

@Serializable
enum class McpAuthStatus { None, Required, SignedIn }

/** Where the browser returns after an MCP sign-in: [Loopback] on desktop and the CLI, [App] on Android and iOS. */
@Serializable
enum class McpRedirect(val uri: String) {
    Loopback("http://localhost:33418/callback"),
    App("dev.kinuvo.app:/mcp/oauth");

    companion object {
        const val LOOPBACK_PORT = 33418
    }
}

@Serializable
data class McpLoginParams(val agentId: String, val name: String, val scope: ConfigScope? = null, val redirect: McpRedirect = McpRedirect.Loopback)

/** Open [url] in a browser; pass where it returns to `mcp.loginComplete`. */
@Serializable
data class McpLogin(val url: String)

/** [callbackUrl] is the full URL the browser was sent back to. */
@Serializable
data class McpLoginCompleteParams(val callbackUrl: String)

@Serializable
data class SaveMcpParams(val agentId: String, val server: McpServerInfo)

/** A null [scope] means the server the Agent uses under that name. */
@Serializable
data class McpParams(val agentId: String, val name: String, val scope: ConfigScope? = null)

/** The tools an MCP server offered when connected, or why it couldn't connect. */
@Serializable
data class McpTestResult(val tools: List<String> = emptyList(), val error: String? = null, val needsLogin: Boolean = false)
