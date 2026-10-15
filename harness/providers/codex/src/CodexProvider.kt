package kinuvo.harness.codex

import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.Model
import kinuvo.harness.core.ModelProvider
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Builds Codex-backed [Model]s sharing one authenticated client; [usage] tracks plan usage. */
class CodexProvider(override val auth: CodexAuth = CodexAuth(), private val attachments: ((kinuvo.protocol.Attachment) -> ByteArray)? = null) : ModelProvider, HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.codex", "ChatGPT / Codex")
    private val _usage = MutableStateFlow<Usage?>(null)
    private val client by lazy { codexLLMClient(auth, _usage) }
    private val discovery = lazy { codexHttpClient(auth::tokens, _usage) }

    override val id = "codex"
    override val name = "ChatGPT / Codex"
    override val catalogId = "openai"
    override suspend fun models() = discoverCodexModels(discovery.value)
    override val usage: StateFlow<Usage?> = _usage
    override val modelProviders get() = listOf(this)

    override suspend fun availability(model: String) =
        if (auth.account().signedIn) CapabilityState() else CapabilityState.unavailable("Sign in to ChatGPT in Settings → Providers", "needs_login")

    override fun model(config: AgentConfig): Model = model(config, null)
    override fun model(config: AgentConfig, info: kinuvo.protocol.ModelInfo?): Model = OpenAIModel(config, client, info, attachments)
    override suspend fun close() { if (discovery.isInitialized()) discovery.value.close() }
}
