package kinuvo.harness.exec

import kinuvo.harness.core.Agent
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.InstructionSection
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.RunContext
import kinuvo.harness.core.artifacts.Artifacts
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shell, file and process tools on each Agent's exec backend, started on first use. */
class ExecPlugin(private val files: AgentFiles, private val secrets: Secrets = Secrets()) : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.exec", "Shell and files")
    override val features = setOf(kinuvo.protocol.ServerFeature.Shell)
    private val lock = Mutex()
    private val backends = mutableMapOf<String, ExecBackend?>()
    private val states = mutableMapOf<String, kinuvo.protocol.CapabilityState>()

    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        val agent = run.agent
        if (agent.config.exec == "none") return PluginTools()
        val preview = object : ExecBackend(agent.config.workspace.ifEmpty { listOf("/") }) {
            override val label = agent.config.ssh?.host ?: agent.config.exec
            override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: kotlin.time.Duration, maxChars: Int): ExecResult =
                error("Inspection cannot execute commands")
        }
        val cached = lock.withLock { backends[agent.id] to states[agent.id] }
        val definitions = execTools(cached.first ?: preview)
        val state = when {
            agent.config.workspace.isEmpty() -> kinuvo.protocol.CapabilityState.unavailable("Exec needs at least one Workspace folder")
            agent.config.exec == "ssh" && agent.config.ssh == null -> kinuvo.protocol.CapabilityState.unavailable("SSH settings are missing")
            else -> cached.second ?: kinuvo.protocol.CapabilityState.unknown("Exec backend has not been checked")
        }
        val result = if (!activate) PluginTools(definitions) else try {
            PluginTools(backend(agent)?.let(::execTools).orEmpty())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { PluginTools(warnings = listOf("Exec backend unavailable: ${e.message}")) }
        val resolved = if (!activate) state else if (result.tools.isEmpty()) kinuvo.protocol.CapabilityState.unavailable(result.warnings.firstOrNull() ?: "Exec backend unavailable") else kinuvo.protocol.CapabilityState()
        if (activate) lock.withLock { states[agent.id] = resolved }
        return result.copy(catalog = definitions.map { kinuvo.protocol.ResolvedTool(it.descriptor.name,
            location = if (agent.config.exec == "ssh") kinuvo.protocol.ExecutionLocation.External else kinuvo.protocol.ExecutionLocation.ServerHost,
            target = agent.config.ssh?.host,
            state = resolved) })
    }

    override suspend fun instructions(agent: Agent): InstructionSection? = runCatching { backend(agent)?.instructions }.getOrNull()

    override suspend fun readFile(agent: Agent, path: String): String? = backend(agent)?.read(path, Artifacts.MAX_CHARS, whole = true)

    override suspend fun release(agentId: String) {
        lock.withLock { states.remove(agentId); backends.remove(agentId) }?.close()
    }

    override suspend fun close() {
        val all = lock.withLock { backends.values.toList().also { backends.clear(); states.clear() } }
        all.forEach { runCatching { it?.close() } }
    }

    private suspend fun backend(agent: Agent): ExecBackend? = lock.withLock {
        backends.getOrPut(agent.id) { ExecBackends.create(agent, files, secrets) }
    }
}
