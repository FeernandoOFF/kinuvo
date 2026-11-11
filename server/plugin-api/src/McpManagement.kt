package kinuvo.server.plugin

import kinuvo.harness.Agent
import kinuvo.protocol.mcp.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.agent.AgentId
/** Lists, edits, tests and signs in to the Agents' MCP servers; a Server without MCP uses [Unavailable]. */
interface McpManagement {
    suspend fun mcpServers(agent: Agent): List<McpServerInfo>
    suspend fun saveMcp(p: SaveMcpParams): McpServerInfo
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
        override suspend fun saveMcp(p: SaveMcpParams): McpServerInfo = unavailable()
        override suspend fun removeMcp(p: McpParams): Unit = unavailable()
        override suspend fun testMcp(p: McpParams): McpTestResult = unavailable()
        override suspend fun loginMcp(p: McpLoginParams): McpLogin = unavailable()
        override suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo = unavailable()
        override suspend fun logoutMcp(p: McpParams): Unit = unavailable()
        override suspend fun removeAgent(id: AgentId) {}
        private fun unavailable(): Nothing = error("MCP is not installed on this Server")
    }
}
