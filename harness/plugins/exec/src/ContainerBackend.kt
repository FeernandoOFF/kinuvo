package kinuvo.harness.exec

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One long-lived container per Agent (`kinuvo-<agentId>`), Workspace folders mounted under /workspace.
 * It is recreated when the image, network or mounts change.
 */
class ContainerBackend(
    agentId: String,
    private val workspace: List<File>,
    private val image: String,
    private val network: Boolean,
    private val env: Map<String, String>,
    private val runtime: String = detectRuntime(),
) : ExecBackend(mountPoints(workspace)) {
    private val name = "kinuvo-$agentId"
    private val spec = listOf(image, network.toString()) + workspace.map { it.canonicalPath }
    private val specLabel = spec.joinToString("|").hashCode().toUInt().toString(16)
    private val mutex = Mutex()
    private var ready = false

    override val label = "a ${File(runtime).name} container ($image, network ${if (network) "on" else "off"})"

    override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: Duration, maxChars: Int): ExecResult {
        ensureRunning()
        // `-e KEY` takes the value from our environment, so it stays out of the process list.
        val envArgs = env.keys.flatMap { listOf("-e", it) }
        return runProcess(listOf(runtime, "exec", "-i", "-w", cwd) + envArgs + listOf(name, "bash", "-lc", script), stdin = stdin, timeout = timeout, env = env, maxChars = maxChars)
    }

    private suspend fun ensureRunning() = mutex.withLock {
        if (ready) return@withLock
        val info = runProcess(listOf(runtime, "info"), timeout = 20.seconds)
        check(info.exitCode == 0) {
            if (File(runtime).name == "podman") "Podman isn't running. Run `podman machine start`." else "Docker isn't running. Start Docker and retry."
        }
        val inspect = runProcess(
            listOf(runtime, "container", "inspect", "-f", "{{.State.Running}} {{index .Config.Labels \"kinuvo.spec\"}}", name),
            timeout = 20.seconds,
        )
        val (running, label) = inspect.output.trim().split(" ").let { it.getOrNull(0) to it.getOrNull(1) }
        when {
            inspect.exitCode != 0 -> create()
            label != specLabel -> { runProcess(listOf(runtime, "rm", "-f", name), timeout = 1.minutes); create() }
            running != "true" -> check(runProcess(listOf(runtime, "start", name), timeout = 1.minutes).exitCode == 0) { "Could not start $name" }
        }
        ready = true
    }

    private suspend fun create() {
        val mounts = workspace.zip(roots).flatMap { (host, inside) -> listOf("-v", "${host.canonicalPath}:$inside") }
        val net = if (network) emptyList() else listOf("--network", "none")
        val r = runProcess(
            listOf(runtime, "run", "-d", "--name", name, "--label", "kinuvo.spec=$specLabel", "-w", roots.first()) +
                net + mounts + listOf(image, "sleep", "infinity"),
            timeout = 10.minutes, // first run may pull the image
        )
        check(r.exitCode == 0) { "Could not create container $name: ${r.output.lines().lastOrNull { it.isNotBlank() }}" }
    }

    companion object {
        /** Absolute path of podman (preferred) or docker; GUI launches often lack Homebrew on PATH. */
        fun detectRuntime(): String {
            val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin")
            return listOf("podman", "docker").firstNotNullOfOrNull { cmd -> dirs.map { File(it, cmd) }.firstOrNull { it.canExecute() } }
                ?.path ?: error("Neither podman nor docker is installed")
        }

        /** `/workspace/<folder-name>`, de-duplicated when two folders share a name. */
        internal fun mountPoints(workspace: List<File>): List<String> {
            val seen = mutableMapOf<String, Int>()
            return workspace.map { f ->
                val n = seen.merge(f.name, 1, Int::plus)!!
                "/workspace/${f.name}${if (n > 1) "-$n" else ""}"
            }
        }
    }
}
