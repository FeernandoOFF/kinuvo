package kinuvo.harness.exec

import kinuvo.harness.core.Agent
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.plugin.InstructionSection
import kinuvo.harness.core.plugin.PluginTools
import kinuvo.harness.core.plugin.RunContext
import kinuvo.harness.core.artifacts.Artifacts
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kinuvo.harness.core.plugin.ExecCheck
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ExecTestResult
import kinuvo.protocol.ExecutionLocation
import kinuvo.protocol.Integration
import kinuvo.protocol.IntegrationOptions
import kinuvo.protocol.PluginDefinition
import kinuvo.protocol.ResolvedTool
import kinuvo.protocol.ServerFeature
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable

/** Shell, file and process tools on each Agent's exec backend, started on first use. */
class ExecPlugin(private val files: AgentFiles, private val secrets: Secrets = Secrets()) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.exec", "Shell and files")
    override val features = setOf(ServerFeature.Shell)
    private val lock = Mutex()
    private val backends = mutableMapOf<String, ExecBackend?>()
    private val states = mutableMapOf<String, CapabilityState>()

    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        val agent = run.agent
        if (agent.config.exec == "none") return PluginTools()
        val preview = object : ExecBackend(agent.config.workspace.ifEmpty { listOf("/") }) {
            override val label = agent.config.ssh?.host ?: agent.config.exec
            override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: Duration, maxChars: Int): ExecResult =
                error("Inspection cannot execute commands")
        }
        val cached = lock.withLock { backends[agent.id] to states[agent.id] }
        val definitions = execTools(cached.first ?: preview)
        val state = when {
            agent.config.workspace.isEmpty() -> CapabilityState.unavailable("Exec needs at least one Workspace folder")
            agent.config.exec == "ssh" && agent.config.ssh == null -> CapabilityState.unavailable("SSH settings are missing")
            else -> cached.second ?: CapabilityState.unknown("Exec backend has not been checked")
        }
        val result = if (!activate) PluginTools(definitions) else try {
            PluginTools(backend(agent)?.let(::execTools).orEmpty())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { PluginTools(warnings = listOf("Exec backend unavailable: ${e.message}")) }
        val resolved = if (!activate) state else if (result.tools.isEmpty()) CapabilityState.unavailable(result.warnings.firstOrNull() ?: "Exec backend unavailable") else CapabilityState()
        if (activate) lock.withLock { states[agent.id] = resolved }
        return result.copy(catalog = definitions.map { ResolvedTool(it.descriptor.name,
            location = if (agent.config.exec == "ssh") ExecutionLocation.External else ExecutionLocation.ServerHost,
            target = agent.config.ssh?.host,
            state = resolved) })
    }

    override val integrations = mapOf(Integration.Exec to setOf(
        IntegrationOptions.EXEC_HOST, IntegrationOptions.EXEC_CONTAINER, IntegrationOptions.EXEC_SSH))

    override val execCheck = ExecCheck { agent, command ->
        val backend = try {
            ExecBackends.create(agent, files, secrets, check = true) ?: return@ExecCheck ExecTestResult(error = "Choose where the shell runs")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return@ExecCheck ExecTestResult(error = e.message) }
        try {
            val files = backend.run("ls -1Ap", null, CHECK_TIMEOUT)
            check(files.exitCode == 0) { files.output.trim().ifEmpty { "Could not list ${backend.roots.first()}" } }
            val result = command?.let { backend.run(it, null, CHECK_TIMEOUT) }
            ExecTestResult(backend.label, files.output.lines().filter { it.isNotBlank() }.take(200),
                command, result?.output, result?.exitCode)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ExecTestResult(backend.label, error = e.message) }
        finally { if (backend is ContainerBackend) kotlinx.coroutines.withContext(NonCancellable) { runCatching { backend.remove() } } }
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

    private companion object {
        val CHECK_TIMEOUT = Duration.parse("30s")
    }

    private suspend fun backend(agent: Agent): ExecBackend? = lock.withLock {
        backends.getOrPut(agent.id) { ExecBackends.create(agent, files, secrets) }
    }
}
