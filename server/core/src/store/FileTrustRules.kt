package kinuvo.server.store

import kinuvo.server.plugin.isFile
import kinuvo.protocol.approval.TrustRules
import kinuvo.server.TrustRulesRepository

/** Trust and Deny rules: each Agent's under `rules` in its `agent.yaml`, the Server's in `config.yaml`. */
class FileTrustRules(private val files: AgentFiles) : TrustRulesRepository {
    override suspend fun get(agentId: String) = if (files.config(agentId).isFile()) files.agentConfig(agentId).rules else TrustRules()
    override suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules) {
        files.updateAgentConfig(agentId) { it.copy(rules = transform(it.rules)) }
    }
    override suspend fun server() = files.config().rules
    override suspend fun updateServer(transform: (TrustRules) -> TrustRules) = files.updateConfig { it.copy(rules = transform(it.rules)) }
}
