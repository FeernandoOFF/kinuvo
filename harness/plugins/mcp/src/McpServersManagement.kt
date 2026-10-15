package kinuvo.harness.mcp

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.McpManagement
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.exists
import kinuvo.harness.core.store.loadEnv
import kinuvo.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** MCP servers from the Agents' `mcp.json` files, signing in through the [McpPlugin]'s [auth]. */
internal class McpServersManagement(private val files: AgentFiles, private val secrets: Secrets, private val auth: McpAuth) : McpManagement {
    private val lock = Mutex()
    private fun agent(id: String) = Agent(id, id, AgentConfig()).also { check(files.config(id).exists()) { "No Agent '$id'" } }
    override suspend fun removeAgent(id: String) { auth.signIns.removeAll(McpPlugin.signInScope(id, ConfigScope.Agent)) }
    override fun onSignInNeeded(listener: suspend (agentId: String, server: String) -> Unit) { auth.onSignInNeeded = listener }
    override suspend fun mcpServers(agent: Agent): List<McpServerInfo> {
        val own = mcpFiles(agent, ConfigScope.Agent).list()
        val all = own + mcpFiles(agent, ConfigScope.Shared).list().map { s -> s.copy(active = own.none { it.name == s.name }) }
        return all.map { s -> if (s.url == null) s else s.copy(auth = auth.status(signInId(agent, s))) }
    }

    override suspend fun saveMcp(p: SaveMcpParams): McpServerInfo = lock.withLock { mcpFiles(agent(p.agentId), p.server.scope).save(p.server) }

    override suspend fun removeMcp(p: McpParams) = lock.withLock {
        val agent = agent(p.agentId)
        val scope = p.scope ?: mcpServer(agent, p.name, null).scope
        check(mcpFiles(agent, scope).remove(p.name)) { "No MCP server '${p.name}' in ${scope.name.lowercase()} config" }
        auth.logout("${McpPlugin.signInScope(agent.id, scope)}/${p.name}")
        Unit
    }

    override suspend fun testMcp(p: McpParams): McpTestResult {
        val agent = agent(p.agentId)
        val server = mcpServer(agent, p.name, p.scope)
        val result = testMcp(server, { secrets.expand(it, files.loadEnv(agent.id), "Kinuvo: testing MCP server ${p.name} (Agent ${agent.id})") },
            auth, McpPlugin.signInScope(agent.id, server.scope))
        return result.copy(error = result.error?.let { secrets.redact(it) })
    }

    override suspend fun loginMcp(p: McpLoginParams): McpLogin {
        val agent = agent(p.agentId)
        val server = mcpServer(agent, p.name, p.scope)
        val url = loginMcp(server, auth, McpPlugin.signInScope(agent.id, server.scope), p.redirect, McpParams(agent.id, server.name, server.scope)) {
            secrets.expand(it, files.loadEnv(agent.id), "Kinuvo: signing in to MCP server ${p.name} (Agent ${agent.id})")
        }
        return McpLogin(url)
    }

    override suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo {
        val owner = auth.complete(p.callbackUrl)
        val agent = agent(owner.agentId)
        return mcpServers(agent).first { it.name == owner.name && it.scope == owner.scope }
    }

    override suspend fun logoutMcp(p: McpParams) {
        val agent = agent(p.agentId)
        auth.logout(signInId(agent, mcpServer(agent, p.name, p.scope)))
    }

    private fun signInId(agent: Agent, server: McpServerInfo) = "${McpPlugin.signInScope(agent.id, server.scope)}/${server.name}"

    private suspend fun mcpServer(agent: Agent, name: String, scope: ConfigScope?) =
        mcpServers(agent).firstOrNull { it.name == name && (if (scope == null) it.active else it.scope == scope) } ?: error("No MCP server '$name'")

    private fun mcpFiles(agent: Agent, scope: ConfigScope) = when (scope) {
        ConfigScope.Agent -> McpFiles(files.mcp(agent.id), scope)
        ConfigScope.Shared -> McpFiles(files.sharedMcp, scope)
        ConfigScope.External -> error("MCP servers are either the Agent's or shared")
    }

}
