package kinuvo.server.plugin

import kinuvo.harness.execution.api.Agent
import kinuvo.protocol.*

/** The most characters an Artifact holds, so a plugin's [HarnessPlugin.readFile] needn't read more. */
const val ARTIFACT_MAX_CHARS = 5_000_000

/** Tries an Agent's exec settings without saving them. */
fun interface ExecCheck {
    suspend fun check(agent: Agent, command: String?): ExecTestResult
}

/**
 * A named part of the harness that an app chooses to include, e.g. exec, MCP or a task provider.
 * Native manifests describe typed contributions and per-run tool discovery without activating them.
 */
interface HarnessPlugin {
    val definition: PluginDefinition
    val instanceId: String get() = "default"
    val manifest: PluginManifest get() {
        val configurable = webSearch + notifications + taskProviders
        return PluginManifest(definition, discoversTools = true,
            providers = taskProviders.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.Tasks, it.settings) } +
                knowledgeSources.map { PluginProviderDefinition(it.id, it.id, PluginProviderKind.Knowledge) } +
                webSearch.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.WebSearch, it.settings) } +
                notifications.map { PluginProviderDefinition(it.id, it.name, PluginProviderKind.Notifications, it.settings) },
            access = PluginAccess(configurable.flatMap { it.hostAccess.network }.toSet(),
                configurable.flatMap { it.hostAccess.files.entries }.groupBy { it.key }.mapValues { (_, entries) ->
                    if (entries.any { it.value == PluginFileAccess.ReadWrite }) PluginFileAccess.ReadWrite else PluginFileAccess.Read
                },
                configurable.flatMap { it.hostAccess.secrets }.toSet()))
    }
    val features: Set<ServerFeature> get() = emptySet()
    val customEndpoints: CustomEndpoints? get() = null
    val taskProviders: List<TaskProvider> get() = emptyList()
    val knowledgeSources: List<KnowledgeSource> get() = emptyList()
    val webSearch: List<WebSearchProvider> get() = emptyList()
    val notifications: List<NotificationProvider> get() = emptyList()
    val apps: McpApps? get() = null
    val mcp: McpManagement? get() = null
    val execCheck: ExecCheck? get() = null
    val browser: BrowserService? get() = null
    val voice: ServerVoice? get() = null
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

val HarnessPlugin.instance get() = PluginInstanceId(definition.id, instanceId)

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
    val catalog: List<ResolvedTool> = tools.map { ResolvedTool(it.descriptor.name) },
) {
    init {
        require(tools.all { tool -> catalog.any { it.id == tool.descriptor.name } }) { "Every runnable tool must be reported" }
    }
}

data class InstructionSection(val title: String, val body: String)

interface McpApps {
    suspend fun resource(agentId: String, ref: McpAppRef): McpAppResource
    suspend fun tool(agentId: String, ref: McpAppRef, name: String): Tool
}

interface CustomEndpoints {
    suspend fun discover(params: CustomEndpointParams): List<ModelInfo>
    suspend fun save(params: CustomEndpointParams): ProviderRef
}
