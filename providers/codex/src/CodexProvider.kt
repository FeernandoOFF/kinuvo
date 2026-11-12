package kinuvo.providers.codex

import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.ProviderAdmin
import kinuvo.server.plugin.ProviderPlugin
import kinuvo.harness.builtin.AgentModel
import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.server.plugin.ImageGeneration
import kinuvo.harness.builtin.koogExecutor
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.usage.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kinuvo.protocol.plugin.PluginDefinition

/** Builds Codex-backed [AgentModel]s sharing one authenticated client; [usage] tracks plan usage. */
class CodexProvider(override val auth: CodexAuth = CodexAuth(), private val attachments: ((Attachment) -> ByteArray)? = null) : ModelSource, ProviderAdmin, ProviderPlugin {
    override val definition = PluginDefinition("kinuvo.codex", "ChatGPT / Codex")
    private val _usage = MutableStateFlow<Usage?>(null)
    private val executor = lazy { koogExecutor(codexLLMClient(auth, _usage)) }
    private val discovery = lazy { codexHttpClient(auth::tokens, _usage) }

    val promptExecutor get() = executor.value
    override val images: ImageGeneration get() = CodexImages(discovery.value)
    override val id = "codex"
    override val name = "ChatGPT / Codex"
    override val catalogId = "openai"
    override suspend fun models() = discoverCodexModels(discovery.value)
    override fun fileTypes(model: ModelInfo?) = responsesFileTypes(model)

    override val usage: StateFlow<Usage?> = _usage
    override val providerAdmins get() = listOf(this)

    override suspend fun availability(model: String) =
        if (auth.account().signedIn) CapabilityState() else CapabilityState.unavailable("Sign in to ChatGPT in Settings → Providers", "needs_login")

    override fun model(config: AgentConfig): AgentModel = model(config, null)
    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel = openAIModel(config, executor.value, info, attachments)
    override suspend fun close() { if (executor.isInitialized()) executor.value.close(); if (discovery.isInitialized()) discovery.value.close() }
}
