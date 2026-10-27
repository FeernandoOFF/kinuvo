package kinuvo.harness.codex

import kinuvo.harness.core.*
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.loadEnv
import kotlinx.io.files.Path

internal fun OpenAIKey(
    files: AgentFiles,
    secrets: Secrets,
    store: Keychain = Keychain.system("kinuvo", "openai") ?: PrivateFile(Path(files.home, "credentials", "openai-key")),
    references: Keychain = PrivateFile(Path(files.home, "credentials", "openai-reference")),
    env: () -> Map<String, String> = { files.loadEnv() },
) = StoredProviderKey("openai", "OpenAI API", "OPENAI_API_KEY", files, secrets, store, references, env)
