package kinuvo.protocol

import kotlinx.serialization.Serializable

/** What the user can change about an Agent; [tools] are the built-in tool groups it may use, [approvals] its risk tolerance (strict | default | relaxed | full). */
@Serializable
data class AgentSettings(
    val model: String,
    val reasoning: String,
    val approvals: String,
    val exec: String,
    val workspace: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val provider: String = "codex",
)

/** An Agent with its settings, the text of its IDENTITY.md and SOUL.md, and its Trust and Deny rules. */
@Serializable
data class AgentDetails(val info: AgentInfo, val settings: AgentSettings, val identity: String, val soul: String, val rules: TrustRules = TrustRules(), val userNotes: String = "")

/** A new Agent from the default template, or a copy of [cloneFrom]'s settings, Identity, Soul and Skills. */
@Serializable
data class CreateAgentParams(val agentId: String, val name: String? = null, val cloneFrom: String? = null)

/** Null fields stay as they are. [name] replaces the title of IDENTITY.md. */
@Serializable
data class UpdateAgentParams(
    val agentId: String,
    val name: String? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val approvals: String? = null,
    val exec: String? = null,
    val workspace: List<String>? = null,
    val tools: List<String>? = null,
    val identity: String? = null,
    val soul: String? = null,
    val rules: TrustRules? = null,
    val provider: String? = null,
    val avatar: String? = null,
    val userNotes: String? = null,
)

/** Where a Skill or MCP server is defined: the Agent's own folder, shared by every Agent, or an extra read-only Skill dir. */
@Serializable
enum class ConfigScope { Agent, Shared, External }

/** A Skill's SKILL.md and the other files bundled with it. */
@Serializable
data class SkillDetails(val info: SkillInfo, val content: String, val files: List<String> = emptyList())

/** A null [scope] means the Skill the Agent uses under that name. */
@Serializable
data class SkillParams(val agentId: String, val name: String, val scope: ConfigScope? = null)

/** Writes SKILL.md ([content]) plus extra text [files] by relative path; [content]'s frontmatter must name the Skill [name]. */
@Serializable
data class SaveSkillParams(
    val agentId: String,
    val name: String,
    val content: String,
    val scope: ConfigScope = ConfigScope.Agent,
    val files: Map<String, String> = emptyMap(),
)

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
