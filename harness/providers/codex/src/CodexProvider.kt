package kinuvo.harness.codex

import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.ModelProvider
import kinuvo.harness.core.koogExecutor
import kinuvo.protocol.Attachment
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Builds Codex-backed [AgentModel]s sharing one authenticated client; [usage] tracks plan usage. */
class CodexProvider(override val auth: CodexAuth = CodexAuth(), private val attachments: ((Attachment) -> ByteArray)? = null) : ModelProvider, HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.codex", "ChatGPT / Codex")
    private val _usage = MutableStateFlow<Usage?>(null)
    private val executor = lazy { koogExecutor(codexLLMClient(auth, _usage)) }
    private val discovery = lazy { codexHttpClient(auth::tokens, _usage) }

    val promptExecutor get() = executor.value
    override val id = "codex"
    override val name = "ChatGPT / Codex"
    override val catalogId = "openai"
    override suspend fun models() = discoverCodexModels(discovery.value)
    override fun reflectionModel(models: List<ModelInfo>): String? = models.filter { it.tools != false && it.state.actionable }
        .minByOrNull { model ->
            when {
                "nano" in model.id.lowercase() -> 0
                "luna" in model.id.lowercase() -> 1
                "mini" in model.id.lowercase() -> 2
                else -> 3
            }
        }?.id?.takeIf { id -> listOf("nano", "luna", "mini").any { it in id.lowercase() } }

    override val usage: StateFlow<Usage?> = _usage
    override val modelProviders get() = listOf(this)

    override suspend fun availability(model: String) =
        if (auth.account().signedIn) CapabilityState() else CapabilityState.unavailable("Sign in to ChatGPT in Settings → Providers", "needs_login")

    override fun model(config: AgentConfig): AgentModel = model(config, null)
    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel = openAIModel(config, executor.value, info, attachments)
    override suspend fun close() { if (executor.isInitialized()) executor.value.close(); if (discovery.isInitialized()) discovery.value.close() }
}
