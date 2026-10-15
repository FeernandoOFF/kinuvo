package kinuvo.harness.core

/** Lists, edits, tests and signs in to the Agents' MCP servers; a Server without MCP uses [Unavailable]. */
import kinuvo.protocol.*

interface McpManagement {
    suspend fun mcpServers(agent: Agent): List<McpServerInfo>
    suspend fun saveMcp(p: SaveMcpParams): McpServerInfo
    suspend fun removeMcp(p: McpParams)
    suspend fun testMcp(p: McpParams): McpTestResult
    suspend fun loginMcp(p: McpLoginParams): McpLogin
    suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo
    suspend fun logoutMcp(p: McpParams)
    suspend fun removeAgent(id: String)
    /** Told once per server when a Routine run finds it needs sign-in, until it's signed in. */
    fun onSignInNeeded(listener: suspend (agentId: String, server: String) -> Unit) {}

    object Unavailable : McpManagement {
        override suspend fun mcpServers(agent: Agent) = emptyList<McpServerInfo>()
        override suspend fun saveMcp(p: SaveMcpParams): McpServerInfo = unavailable()
        override suspend fun removeMcp(p: McpParams): Unit = unavailable()
        override suspend fun testMcp(p: McpParams): McpTestResult = unavailable()
        override suspend fun loginMcp(p: McpLoginParams): McpLogin = unavailable()
        override suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo = unavailable()
        override suspend fun logoutMcp(p: McpParams): Unit = unavailable()
        override suspend fun removeAgent(id: String) {}
        private fun unavailable(): Nothing = error("MCP is not installed on this Server")
    }
}
