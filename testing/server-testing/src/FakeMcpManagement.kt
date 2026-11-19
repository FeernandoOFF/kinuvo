package kinuvo.testing.server

import kinuvo.harness.Agent
import kinuvo.protocol.agent.ConfigScope
import kinuvo.protocol.mcp.McpServerInfo
import kinuvo.protocol.mcp.SaveMcpParams
import kinuvo.server.plugin.McpManagement
import kinuvo.server.plugin.requireNewServer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeMcpManagement(val entries: MutableList<McpServerInfo> = mutableListOf()) : McpManagement by McpManagement.Unavailable {
    private val lock = Mutex()

    override suspend fun mcpServers(agent: Agent) = lock.withLock { inventory() }

    override suspend fun saveMcp(p: SaveMcpParams, createOnly: Boolean): McpServerInfo = lock.withLock {
        if (createOnly) inventory().requireNewServer(p)
        entries.removeAll { it.scope == p.server.scope && it.name == p.server.name }
        entries += p.server
        p.server
    }

    private fun inventory() = entries.map { server ->
        if (server.scope == ConfigScope.Shared) server.copy(active = entries.none { it.scope == ConfigScope.Agent && it.name == server.name })
        else server
    }
}
