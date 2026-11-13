package kinuvo.testing.server

import kinuvo.protocol.secret.SecretEntry
import kinuvo.protocol.secret.SecretProviderStatus
import kinuvo.protocol.secret.secretPath
import kinuvo.server.plugin.SecretProvider

/** A password manager holding [fields] by path (vault, item, field); [reads] records each resolved reference and reason. */
class FakeSecretProvider(
    private val fields: Map<List<String>, String>,
    override val scheme: String = "pass",
    override val name: String = "Fake Pass",
    var connected: Boolean = true,
) : SecretProvider {
    val reads = mutableListOf<Pair<String, String>>()

    override suspend fun resolve(uri: String, reason: String): String {
        reads += uri to reason
        return fields[secretPath(uri)] ?: error("No such field")
    }

    override suspend fun status() = SecretProviderStatus(scheme, name, installed = true, connected = connected)
    override suspend fun connect(token: String) { connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun browse(path: List<String>) =
        fields.keys.filter { it.size > path.size && it.take(path.size) == path }.map { SecretEntry(it[path.size]) }.distinct()
}
