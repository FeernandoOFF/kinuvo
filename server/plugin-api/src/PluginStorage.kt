package kinuvo.server.plugin

import kotlinx.io.files.Path
import kinuvo.protocol.agent.AgentId

/** What plugins read of the Server's home: where it is, its MCP files, `${VAR}` values and settings of theirs. */
interface PluginStorage {
    val home: Path
    val sharedMcp: Path
    fun mcp(id: AgentId): Path
    fun agentExists(id: AgentId): Boolean
    /** Variables for `${VAR}` expansion: `<home>/.env`, then the Agent's `.env`, then [process] (strongest). */
    fun loadEnv(agentId: AgentId? = null, process: Map<String, String> = processEnv()): Map<String, String>
    /** The `options` of `config.yaml`'s `knowledge`. */
    fun knowledgeOptions(): Map<String, String>
}
