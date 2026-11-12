package kinuvo.server.plugin

import kinuvo.protocol.model.ProviderApiKeyStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

class StoredProviderKey(
    private val id: String,
    private val name: String,
    private val environmentKey: String,
    private val secrets: SecretResolver,
    private val store: Keychain,
    private val references: Keychain,
    private val env: () -> Map<String, String>,
) : ProviderApiKey {
    private val lock = Mutex()

    private suspend fun value() = references.read()?.takeIf { it.isNotBlank() }
        ?: store.read()?.takeIf { it.isNotBlank() }
        ?: env()[environmentKey]?.takeIf { it.isNotBlank() }

    suspend fun resolve(): String? = lock.withLock {
        value()?.let { secrets.expand(it, env(), "Kinuvo: $name") }
    }

    override suspend fun status(): ProviderApiKeyStatus = lock.withLock {
        val reference = references.read()?.takeIf { it.isNotBlank() }
        val stored = if (reference == null) store.read()?.takeIf { it.isNotBlank() } else null
        val value = reference ?: stored ?: env()[environmentKey]?.takeIf { it.isNotBlank() }
        ProviderApiKeyStatus(value != null,
            if (reference != null) "${references.name} (reference only)" else if (stored != null) store.name else "$environmentKey in the Server environment or .env",
            reference != null || stored != null, value?.takeIf { SecretReferences.isReference(it) })
    }

    override suspend fun save(value: String) = lock.withLock {
        val trimmed = value.trim()
        require(trimmed.length <= 8192) { "API key or reference is too long" }
        when {
            trimmed.isEmpty() -> { references.delete(); store.delete() }
            SecretReferences.isReference(trimmed) -> {
                secrets.expand(trimmed, env(), "Kinuvo: configuring $name")
                references.write(trimmed)
                store.delete()
            }
            else -> {
                require(trimmed.none { it.isWhitespace() }) { "Enter an API key or a single Secret reference" }
                store.write(trimmed)
                references.delete()
            }
        }
    }
}

/** Opens the API key stores providers keep under the Server's home; [system] gives an account's OS keychain, or null when the host has none. */
class ProviderKeys(
    val storage: PluginStorage,
    val secrets: SecretResolver,
    private val system: (account: String) -> Keychain? = { null },
) {
    fun store(account: String): Keychain = system(account) ?: PrivateFile(Path(storage.home, "credentials", "$account-key"))
    fun reference(account: String): Keychain = PrivateFile(Path(storage.home, "credentials", "$account-reference"))

    fun key(id: String, name: String, environmentKey: String, env: () -> Map<String, String> = { storage.loadEnv() }) =
        StoredProviderKey(id, name, environmentKey, secrets, store(id), reference(id), env)
}
