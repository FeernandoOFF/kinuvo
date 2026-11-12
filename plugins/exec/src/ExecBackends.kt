package kinuvo.plugins.exec

import kinuvo.harness.Agent
import kinuvo.harness.ExecModes
import kinuvo.server.plugin.SecretResolver
import kinuvo.server.plugin.PluginStorage
import java.io.File

object ExecBackends {
    /** The Agent's backend, or null when `exec: none`; a [check] backend gets its own container and SSH connection. */
    suspend fun create(agent: Agent, files: PluginStorage, secrets: SecretResolver = SecretResolver.None, check: Boolean = false): ExecBackend? {
        val cfg = agent.config
        if (cfg.exec == ExecModes.NONE) return null
        val workspace = cfg.workspace.map(::expandHome)
        require(workspace.isNotEmpty()) { "exec: ${cfg.exec} needs at least one workspace folder in agent.yaml" }
        val vars = files.loadEnv(agent.id)
        val env = cfg.env.mapValues { secrets.expand(it.value, vars, "Kinuvo: exec environment (Agent ${agent.id})") }
        fun localDirs() = workspace.map(::File).onEach { require(it.isDirectory) { "Workspace folder $it does not exist" } }
        return when (cfg.exec) {
            ExecModes.HOST -> HostBackend(localDirs())
            ExecModes.CONTAINER -> ContainerBackend(agent.id, localDirs(), cfg.container.image, cfg.container.network, env,
                name = if (check) "kinuvo_check_${java.util.UUID.randomUUID()}" else "kinuvo-${agent.id}")
            ExecModes.SSH -> {
                val ssh = requireNotNull(cfg.ssh) { "exec: ssh needs an ssh: {host, user?, port?, identityFile?} section" }
                SshBackend(ssh.host, ssh.user, ssh.port, ssh.identityFile, cfg.workspace, env, if (check) null else File(files.home.toString(), "ssh"))
            }
            else -> error("exec: ${cfg.exec} is not supported yet")
        }
    }

    internal fun expandHome(path: String) =
        if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path
}
