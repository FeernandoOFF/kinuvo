package kinuvo.harness.codex

import kinuvo.harness.core.*
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.loadEnv
import kinuvo.protocol.ProviderApiKeyStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

internal class OpenAIKey(
    private val files: AgentFiles,
    private val secrets: Secrets,
    private val store: Keychain = Keychain.system("kinuvo", "openai") ?: PrivateFile(Path(files.home, "credentials", "openai-key")),
    private val references: Keychain = PrivateFile(Path(files.home, "credentials", "openai-reference")),
    private val env: () -> Map<String, String> = { files.loadEnv() },
) : ProviderApiKey {
    private val lock = Mutex()

    private suspend fun value() = references.read()?.takeIf { it.isNotBlank() }
        ?: store.read()?.takeIf { it.isNotBlank() }
        ?: env()["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }

    suspend fun resolve(): String? = lock.withLock {
        value()?.let { secrets.expand(it, env(), "Kinuvo: OpenAI API") }
    }

    override suspend fun status(): ProviderApiKeyStatus = lock.withLock {
        val reference = references.read()?.takeIf { it.isNotBlank() }
        val stored = if (reference == null) store.read()?.takeIf { it.isNotBlank() } else null
        val value = reference ?: stored ?: env()["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }
        ProviderApiKeyStatus(value != null,
            if (reference != null) "${references.name} (reference only)" else if (stored != null) store.name else "OPENAI_API_KEY in the Server environment or .env",
            reference != null || stored != null, value?.takeIf { Secrets.isReference(it) })
    }

    override suspend fun save(value: String) = lock.withLock {
        val trimmed = value.trim()
        require(trimmed.length <= 8192) { "API key or reference is too long" }
        when {
            trimmed.isEmpty() -> { references.delete(); store.delete() }
            Secrets.isReference(trimmed) -> {
                secrets.expand(trimmed, env(), "Kinuvo: configuring OpenAI API")
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
