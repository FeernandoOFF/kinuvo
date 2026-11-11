package kinuvo.plugins.mcp

import kinuvo.server.plugin.modelAlias
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.McpApps
import kinuvo.protocol.mcp.McpAppRef
import kinuvo.server.plugin.McpManagement
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.SecretResolver
import kinuvo.server.plugin.PluginStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kinuvo.protocol.agent.ConfigScope
import kinuvo.protocol.capability.Authorization
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.capability.ExecutionLocation
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.capability.IntegrationOptions
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.protocol.capability.ResolvedTool
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.Support

/** Each Agent's MCP servers (the shared ones it uses, then its own) as tools; servers connect on first use. */
class McpPlugin(
    private val files: PluginStorage,
    private val secrets: SecretResolver = SecretResolver.None,
    keychain: Keychain? = null,
    val auth: McpAuth = McpAuth(defaultSignIns(files, keychain)),
) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.mcp", "MCP")
    override val features = setOf(ServerFeature.Mcp)
    override val mcp: McpManagement = McpServersManagement(files, secrets, auth)
    override val catalogued = true
    override val integrations = mapOf(Integration.Mcp to
        setOfNotNull(IntegrationOptions.MCP_HTTP, IntegrationOptions.MCP_STDIO.takeIf { stdioSupported }))
    private val lock = Mutex()
    private val managers = mutableMapOf<String, McpManager>()

    override val apps = object : McpApps {
        override suspend fun resource(agentId: String, ref: McpAppRef) = manager(agentId).resource(ref)
        override suspend fun tool(agentId: String, ref: McpAppRef, name: String) = manager(agentId).appTool(ref, name)
    }

    private suspend fun manager(id: String) = lock.withLock {
        managers.getOrPut(id) {
            McpManager(
                listOf(files.sharedMcp, files.mcp(id)),
                { server, value -> secrets.expand(value, files.loadEnv(id), "Kinuvo: MCP server $server (Agent $id)") },
                auth, signInScopes(id), id,
            )
        }
    }

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
            val state = if (warning == null) CapabilityState.unknown("MCP tools have not been discovered")
                else CapabilityState.unavailable(warning).copy(support = Support.Unknown,
                    authorization = if (cfg is McpServerConfig.Http && auth.isRequired(cfg.signInId)) Authorization.NeedsAccess else Authorization.Allowed)
            (names ?: listOf("*")).map { local ->
                val identity = McpTool.identity(cfg, local)
                val name = identity.modelAlias()
                ResolvedTool(name, local, location = ExecutionLocation.External, target = cfg.name, state = state)
                    .copy(identity = identity)
            }
        })

    private suspend fun activate(run: RunContext): PluginTools {
        val manager = manager(run.agent.id)
        val (tools, warnings) = manager.tools()
        if (run.routine != null) manager.needingSignIn().forEach { auth.signInNeeded(run.agent.id, it) }
        return PluginTools(tools, warnings, tools.map { ResolvedTool(it.descriptor.name,
            it.displayName, location = ExecutionLocation.External, identity = it.identity) })
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
