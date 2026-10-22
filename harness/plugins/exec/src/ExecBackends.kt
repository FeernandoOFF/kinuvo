package kinuvo.harness.exec

import kinuvo.harness.core.Agent
import kinuvo.harness.core.ExecModes
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.loadEnv
import java.io.File

object ExecBackends {
    /** The Agent's backend, or null when `exec: none`; a [check] backend gets its own container and SSH connection. */
    suspend fun create(agent: Agent, files: AgentFiles, secrets: Secrets = Secrets(), check: Boolean = false, resourceId: String = agent.id): ExecBackend? {
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
                name = if (check) "kinuvo_check_${java.util.UUID.randomUUID()}" else "kinuvo-$resourceId")
            ExecModes.SSH -> {
                val ssh = requireNotNull(cfg.ssh) { "exec: ssh needs an ssh: {host, user?, port?, identityFile?} section" }
                SshBackend(ssh.host, ssh.user, ssh.port, ssh.identityFile, cfg.workspace, env, if (check) null else File(files.home.toString(), "ssh/" + java.security.MessageDigest.getInstance("SHA-256").digest(resourceId.toByteArray()).take(6).joinToString("") { "%02x".format(it) }))
            }
            else -> error("exec: ${cfg.exec} is not supported yet")
        }
    }

    internal fun expandHome(path: String) =
        if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path
}
