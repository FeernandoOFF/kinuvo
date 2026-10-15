package kinuvo.harness.core.store

import com.charleskorn.kaml.Yaml
import kinuvo.protocol.TrustRules
import kinuvo.harness.core.TrustRulesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/** Trust rules in each Agent's `approvals.yaml`, and the Server's in the home folder's. */
class FileTrustRules(private val files: AgentFiles) : TrustRulesRepository {
    private val lock = Mutex()

    override suspend fun get(agentId: String) = read(files.trustRules(agentId))
    override suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules) = update(files.trustRules(agentId), transform)
    override suspend fun server() = read(files.serverTrustRules)
    override suspend fun updateServer(transform: (TrustRules) -> TrustRules) = update(files.serverTrustRules, transform)

    private fun read(file: Path): TrustRules =
        file.readTextOrNull()?.takeIf { it.isNotBlank() }?.let { Yaml.default.decodeFromString(TrustRules.serializer(), it) } ?: TrustRules()

    private suspend fun update(file: Path, transform: (TrustRules) -> TrustRules) = lock.withLock {
        val old = read(file)
        val new = transform(old)
        if (new != old) writeAtomically(file, Yaml.default.encodeToString(TrustRules.serializer(), new) + "\n")
    }
}
