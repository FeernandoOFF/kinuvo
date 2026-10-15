package kinuvo.harness.core.store

import com.charleskorn.kaml.Yaml
import kinuvo.protocol.TrustRules
import kinuvo.harness.core.TrustRulesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Trust rules in each Agent's `approvals.yaml`. */
class FileTrustRules(private val files: AgentFiles) : TrustRulesRepository {
    private val lock = Mutex()

    override suspend fun get(agentId: String): TrustRules =
        files.trustRules(agentId).readTextOrNull()?.takeIf { it.isNotBlank() }
            ?.let { Yaml.default.decodeFromString(TrustRules.serializer(), it) } ?: TrustRules()

    override suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules) = lock.withLock {
        val old = get(agentId)
        val new = transform(old)
        if (new != old) writeAtomically(files.trustRules(agentId), Yaml.default.encodeToString(TrustRules.serializer(), new) + "\n")
    }
}
