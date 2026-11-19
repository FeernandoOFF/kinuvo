package kinuvo.server.plugin

import io.ktor.http.Url
import kinuvo.harness.Agent
import kinuvo.protocol.mcp.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.ConfigScope
/** Lists, edits, tests and signs in to the Agents' MCP servers; a Server without MCP uses [Unavailable]. */
interface McpManagement {
    suspend fun mcpServers(agent: Agent): List<McpServerInfo>
    /** With [createOnly], reject name and effective endpoint conflicts under the save lock. */
    suspend fun saveMcp(p: SaveMcpParams, createOnly: Boolean = false): McpServerInfo
    suspend fun removeMcp(p: McpParams)
    suspend fun testMcp(p: McpParams): McpTestResult
    suspend fun loginMcp(p: McpLoginParams): McpLogin
    suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo
    suspend fun logoutMcp(p: McpParams)
    suspend fun removeAgent(id: AgentId)
    /** Told once per server when a Routine run finds it needs sign-in, until it's signed in. */
    fun onSignInNeeded(listener: suspend (agentId: AgentId, server: String) -> Unit) {}

    object Unavailable : McpManagement {
        override suspend fun mcpServers(agent: Agent) = emptyList<McpServerInfo>()
        override suspend fun saveMcp(p: SaveMcpParams, createOnly: Boolean): McpServerInfo = unavailable()
        override suspend fun removeMcp(p: McpParams): Unit = unavailable()
        override suspend fun testMcp(p: McpParams): McpTestResult = unavailable()
        override suspend fun loginMcp(p: McpLoginParams): McpLogin = unavailable()
        override suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo = unavailable()
        override suspend fun logoutMcp(p: McpParams): Unit = unavailable()
        override suspend fun removeAgent(id: AgentId) {}
        private fun unavailable(): Nothing = error("MCP is not installed on this Server")
    }
}

fun List<McpServerInfo>.effectiveFor(id: AgentId) = filter {
    it.active && (it.scope != ConfigScope.Shared || it.agents == null || id in it.agents.orEmpty())
}

fun List<McpServerInfo>.requireNewServer(p: SaveMcpParams) {
    require(none { it.name == p.server.name } && effectiveFor(p.agentId).none {
        p.server.url != null && it.url?.normalizedMcpEndpoint() == p.server.url?.normalizedMcpEndpoint()
    }) { "MCP server is already configured or its name is reserved; use mcp_list, mcp_test or mcp_login. Intentional replacement requires custom mcp_add with Approval." }
}

fun String.normalizedMcpEndpoint(): String {
    val url = Url(this)
    val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
    val suffix = substringAfter("://").dropWhile { it != '/' && it != '?' && it != '#' }
    val path = suffix.takeWhile { it != '?' && it != '#' }
    return "${url.protocol.name.lowercase()}://${url.host.lowercase()}$port${path.trimEnd('/')}${suffix.drop(path.length)}"
}
