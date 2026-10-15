package kinuvo.harness.mcp

import kinuvo.harness.core.modelAlias
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.McpManagement
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.RunContext
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.loadEnv
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kinuvo.protocol.ConfigScope

/** Each Agent's MCP servers (the shared ones it uses, then its own) as tools; servers connect on first use. */
class McpPlugin(
    private val files: AgentFiles,
    private val secrets: Secrets = Secrets(),
    val auth: McpAuth = McpAuth(defaultSignIns(files)),
) : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.mcp", "MCP")
    override val features = setOf(kinuvo.protocol.ServerFeature.Mcp)
    override val mcp: McpManagement = McpServersManagement(files, secrets, auth)
    override val catalogued = true
    override val integrations = mapOf(kinuvo.protocol.Integration.Mcp to
        setOfNotNull(kinuvo.protocol.IntegrationOptions.MCP_HTTP, kinuvo.protocol.IntegrationOptions.MCP_STDIO.takeIf { stdioSupported }))
    private val lock = Mutex()
    private val managers = mutableMapOf<String, McpManager>()

    private data class Inspection(val configs: List<McpServerConfig>, val generations: List<Int>, val result: PluginTools)
    private val inspections = mutableMapOf<String, Inspection>()

    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        val id = run.agent.id
        val (configs, errors) = McpConfig.load(listOf(files.sharedMcp, files.mcp(id)), signInScopes(id), id) { _, value -> value }
        val generations = configs.map { (it as? McpServerConfig.Http)?.let { cfg -> auth.generation(cfg.signInId) } ?: 0 }
        if (!activate) return lock.withLock {
            inspections[id]?.takeIf { it.configs == configs && it.generations == generations }?.result ?: unknown(configs, errors)
        }
        val result = activate(run)
        val reported = result.copy(catalog = result.catalog + unknown(configs.filter { cfg ->
            result.warnings.any { "MCP server '${cfg.name}'" in it }
        }, result.warnings).catalog)
        lock.withLock { inspections[id] = Inspection(configs, generations, reported) }
        return reported
    }

    private fun unknown(configs: List<McpServerConfig>, warnings: List<String>): PluginTools = PluginTools(warnings = warnings,
        catalog = configs.flatMap { cfg ->
            val names = cfg.includeTools?.filter(cfg::allows)
            val warning = warnings.firstOrNull { "MCP server '${cfg.name}'" in it }
            val state = if (warning == null) kinuvo.protocol.CapabilityState.unknown("MCP tools have not been discovered")
                else kinuvo.protocol.CapabilityState.unavailable(warning).copy(support = kinuvo.protocol.Support.Unknown,
                    authorization = if (cfg is McpServerConfig.Http && auth.isRequired(cfg.signInId)) kinuvo.protocol.Authorization.NeedsAccess else kinuvo.protocol.Authorization.Allowed)
            (names ?: listOf("*")).map { local ->
                val identity = McpTool.identity(cfg, local)
                val name = identity.modelAlias()
                kinuvo.protocol.ResolvedTool(name, local, location = kinuvo.protocol.ExecutionLocation.External, target = cfg.name, state = state)
                    .copy(identity = identity)
            }
        })

    private suspend fun activate(run: RunContext): PluginTools {
        val manager = lock.withLock {
            val id = run.agent.id
            managers.getOrPut(id) {
                McpManager(
                    listOf(files.sharedMcp, files.mcp(id)),
                    { server, value -> secrets.expand(value, files.loadEnv(id), "Kinuvo: MCP server $server (Agent $id)") },
                    auth, signInScopes(id), id,
                )
            }
        }
        val (tools, warnings) = manager.tools()
        if (run.routine != null) manager.needingSignIn().forEach { auth.signInNeeded(run.agent.id, it) }
        return PluginTools(tools, warnings, tools.map { kinuvo.protocol.ResolvedTool(it.descriptor.name,
            it.displayName, location = kinuvo.protocol.ExecutionLocation.External, identity = it.identity) })
    }

    override suspend fun release(agentId: String) {
        lock.withLock { inspections.remove(agentId); managers.remove(agentId) }?.close()
    }

    override suspend fun close() {
        val all = lock.withLock { managers.values.toList().also { managers.clear(); inspections.clear() } }
        all.forEach { runCatching { it.close() } }
        auth.close()
    }

    companion object {
        /** Sign-ins follow the config's scope: one for a shared server, one per Agent for its own. */
        fun signInScope(agentId: String, scope: ConfigScope) = if (scope == ConfigScope.Shared) "shared" else "agents/$agentId"

        fun signInScopes(agentId: String) = listOf(signInScope(agentId, ConfigScope.Shared), signInScope(agentId, ConfigScope.Agent))
    }
}
