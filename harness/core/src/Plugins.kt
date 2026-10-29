package kinuvo.harness.core

import kinuvo.harness.core.knowledge.KnowledgeSource
import kinuvo.harness.core.tasks.TaskProvider
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.WebSearchProvider
import kinuvo.protocol.*
import kotlinx.coroutines.flow.StateFlow

/** Builds the [AgentModel] an Agent's config asks for; [id] is what `provider:` names in agent.yaml. */
interface ModelProvider {
    val id: String
    val name: String get() = id
    /** Provider key in Models.dev, for optional metadata enrichment. */
    val catalogId: String? get() = null
    suspend fun models(): List<kinuvo.protocol.ModelInfo> = emptyList()
    suspend fun availability(model: String): kinuvo.protocol.CapabilityState = kinuvo.protocol.CapabilityState()
    /** Native file MIME types accepted by this endpoint, separately from model input modalities. */
    fun fileTypes(model: ModelInfo?): Set<String> = if (model?.input.orEmpty().any { it == "file" || it == "document" })
        ATTACHMENT_MIME_TYPES.filter { attachmentInput(it) == "file" }.toSet() else emptySet()
    fun model(config: AgentConfig): AgentModel
    fun model(config: AgentConfig, info: kinuvo.protocol.ModelInfo?): AgentModel = model(config)
    /** Plan usage, for providers that report it. */
    val usage: StateFlow<Usage?>? get() = null
    /** The [tools] [model] can take, e.g. fewer and simpler ones for a small model; the rest aren't offered. */
    fun usableTools(model: String, tools: List<ai.koog.agents.core.tools.ToolDescriptor>) = tools
    /** Signs in to the provider's account; null when it needs no sign-in. */
    val auth: ProviderAuth? get() = null
    val apiKey: ProviderApiKey? get() = null
}

/** Tries an Agent's exec settings without saving them. */
fun interface ExecCheck {
    suspend fun check(agent: Agent, command: String?): kinuvo.protocol.ExecTestResult
}

/** API keys or Secret references, configured on the Server without exposing saved values. */
interface ProviderApiKey {
    suspend fun status(): kinuvo.protocol.ProviderApiKeyStatus
    /** An empty [value] removes the saved setting. */
    suspend fun save(value: String)
}

/** A model provider's account sign-in, done with a device code so it works from any client or a headless Server. */
interface ProviderAuth {
    suspend fun account(): kinuvo.protocol.ProviderAccount
    /** Signs in, handing the URL and code to enter to [show]; returns once signed in, or throws when it fails or expires. */
    suspend fun login(show: (kinuvo.protocol.DeviceCode) -> Unit)
    suspend fun logout()
}

/**
 * A named part of the harness that an app chooses to include, e.g. exec, MCP or a model provider.
 * Native manifests describe typed contributions and per-run tool discovery without activating them.
 */
interface HarnessPlugin {
    val definition: kinuvo.protocol.PluginDefinition
    val instanceId: String get() = "default"
    val manifest: PluginManifest get() {
        val configurable = webSearch + notifications
        return PluginManifest(definition, discoversTools = true,
            providers = modelProviders.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.Model) } +
                taskProviders.map { PluginProviderDefinition(it.id, it.id, PluginProviderKind.Tasks) } +
                knowledgeSources.map { PluginProviderDefinition(it.id, it.id, PluginProviderKind.Knowledge) } +
                webSearch.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.WebSearch, it.settings) } +
                notifications.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.Notifications, it.settings) },
            access = PluginAccess(configurable.flatMap { it.hostAccess.network }.toSet(),
                configurable.flatMap { it.hostAccess.files.entries }.groupBy { it.key }.mapValues { (_, entries) ->
                    if (entries.any { it.value == PluginFileAccess.ReadWrite }) PluginFileAccess.ReadWrite else PluginFileAccess.Read
                },
                configurable.flatMap { it.hostAccess.secrets }.toSet()))
    }
    val features: Set<kinuvo.protocol.ServerFeature> get() = emptySet()
    val modelProviders: List<ModelProvider> get() = emptyList()
    val taskProviders: List<TaskProvider> get() = emptyList()
    val knowledgeSources: List<KnowledgeSource> get() = emptyList()
    val webSearch: List<WebSearchProvider> get() = emptyList()
    val notifications: List<NotificationProvider> get() = emptyList()
    val mcp: McpManagement? get() = null
    val execCheck: ExecCheck? get() = null
    val browser: BrowserService? get() = null
    /** Whether its tools go to the tool catalog once an Agent has too many to send each to the model, e.g. MCP servers'. */
    val catalogued: Boolean get() = false
    /** Options it adds to integrations its typed providers don't describe, e.g. the exec backends it can run. */
    val integrations: Map<Integration, Set<String>> get() = emptyMap()

    /** Inspection must not start resources, resolve Secret references or request sign-in. Activation may discover tools. */
    suspend fun prepare(run: RunContext, activate: Boolean): PluginTools = PluginTools()

    /** A section for a new Session's instructions, e.g. where the shell tools run. */
    suspend fun instructions(agent: Agent): InstructionSection? = null

    /** A file from the Agent's Workspace, e.g. to copy into an Artifact; null when the plugin has no Workspace for it. */
    suspend fun readFile(agent: Agent, path: String): String? = null

    /** Stops what the plugin runs for an Agent, e.g. after its settings changed or before it's deleted. */
    suspend fun release(agentId: String) {}

    suspend fun close() {}
}

val HarnessPlugin.instance get() = kinuvo.protocol.PluginInstanceId(definition.id, instanceId)

fun List<HarnessPlugin>.checkPluginInstances() {
    val instances = map { it.instance }
    require(instances.distinct().size == instances.size) { "Duplicate plugin instances" }
    forEach {
        val manifest = it.manifest
        require(manifest.plugin == it.definition) { "Manifest belongs to another plugin" }
        manifest.checkSupported()
    }
}

/** One run: [sessionId] is the Session it runs in; [routine] is set for a Routine's run; [taskId] for a dispatched Task. */
data class RunContext(val agent: Agent, val sessionId: String?, val routine: Routine? = null, val taskId: String? = null)

data class PluginTools(
    val tools: List<Tool> = emptyList(),
    val warnings: List<String> = emptyList(),
    val catalog: List<kinuvo.protocol.ResolvedTool> = tools.map { kinuvo.protocol.ResolvedTool(it.descriptor.name) },
) {
    init {
        require(tools.all { tool -> catalog.any { it.id == tool.descriptor.name } }) { "Every runnable tool must be reported" }
    }
}

data class InstructionSection(val title: String, val body: String)
