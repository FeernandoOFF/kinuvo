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
    private data class Key(val agentId: String, val exec: String, val workspace: List<String>, val container: kinuvo.protocol.ContainerSettings,
        val ssh: kinuvo.protocol.SshSettings?, val env: Map<String, String>)
    private fun key(agent: Agent) = agent.config.let { Key(agent.id, it.exec, it.workspace, it.container, it.ssh, it.env) }
    private val backends = mutableMapOf<Key, ExecBackend?>()
    private val states = mutableMapOf<Key, kinuvo.protocol.CapabilityState>()

    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        val agent = run.agent
        if (agent.config.exec == "none") return PluginTools()
        val preview = object : ExecBackend(agent.config.workspace.ifEmpty { listOf("/") }) {
            override val label = agent.config.ssh?.host ?: agent.config.exec
            override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: kotlin.time.Duration, maxChars: Int): ExecResult =
                error("Inspection cannot execute commands")
        }
        val cached = lock.withLock { backends[key(agent)] to states[key(agent)] }
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
        if (activate) lock.withLock { states[key(agent)] = resolved }
        return result.copy(catalog = definitions.map { kinuvo.protocol.ResolvedTool(it.descriptor.name,
            location = if (agent.config.exec == "ssh") kinuvo.protocol.ExecutionLocation.External else kinuvo.protocol.ExecutionLocation.ServerHost,
            target = agent.config.ssh?.host,
            state = resolved) })
    }

    override val integrations = mapOf(kinuvo.protocol.Integration.Exec to setOf(
        kinuvo.protocol.IntegrationOptions.EXEC_HOST, kinuvo.protocol.IntegrationOptions.EXEC_CONTAINER, kinuvo.protocol.IntegrationOptions.EXEC_SSH))

    override val execCheck = kinuvo.harness.core.ExecCheck { agent, command ->
        val backend = try {
            ExecBackends.create(agent, files, secrets, check = true) ?: return@ExecCheck kinuvo.protocol.ExecTestResult(error = "Choose where the shell runs")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { return@ExecCheck kinuvo.protocol.ExecTestResult(error = e.message) }
        try {
            val files = backend.run("ls -1Ap", null, CHECK_TIMEOUT)
            check(files.exitCode == 0) { files.output.trim().ifEmpty { "Could not list ${backend.roots.first()}" } }
            val result = command?.let { backend.run(it, null, CHECK_TIMEOUT) }
            kinuvo.protocol.ExecTestResult(backend.label, files.output.lines().filter { it.isNotBlank() }.take(200),
                command, result?.output, result?.exitCode)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { kinuvo.protocol.ExecTestResult(backend.label, error = e.message) }
        finally { if (backend is ContainerBackend) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { backend.remove() } } }
    }

    override suspend fun instructions(agent: Agent): InstructionSection? = runCatching { backend(agent)?.instructions }.getOrNull()

    override suspend fun readFile(agent: Agent, path: String): String? = backend(agent)?.read(path, Artifacts.MAX_CHARS, whole = true)

    override suspend fun release(agentId: String) {
        val removed = lock.withLock {
            states.keys.removeAll { it.agentId == agentId }
            backends.keys.filter { it.agentId == agentId }.map { backends.remove(it) }
        }
        removed.forEach { it?.close() }
    }

    override suspend fun close() {
        val all = lock.withLock { backends.values.toList().also { backends.clear(); states.clear() } }
        all.forEach { runCatching { it?.close() } }
    }

    private companion object {
        val CHECK_TIMEOUT = kotlin.time.Duration.parse("30s")
    }

    private suspend fun backend(agent: Agent): ExecBackend? = lock.withLock {
        val key = key(agent)
        backends.getOrPut(key) {
            val encoded = kinuvo.protocol.ProtocolJson.encodeToString(kinuvo.harness.core.AgentConfig.serializer(),
                kinuvo.harness.core.AgentConfig(exec = key.exec, workspace = key.workspace, container = key.container,
                    ssh = key.ssh, env = key.env.toSortedMap()))
            val suffix = java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            ExecBackends.create(agent, files, secrets, resourceId = "${agent.id}_$suffix")
        }
    }
}
