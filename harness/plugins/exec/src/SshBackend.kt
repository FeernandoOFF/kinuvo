package kinuvo.harness.exec

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs commands on a remote machine with the system `ssh`, sharing one connection via ControlMaster. */
class SshBackend(
    private val host: String,
    private val user: String?,
    private val port: Int?,
    private val identityFile: String?,
    /** Absolute paths on the remote machine. */
    workspace: List<String>,
    private val env: Map<String, String>,
    private val controlDir: File,
) : ExecBackend(workspace) {
    private val target = user?.let { "$it@$host" } ?: host

    override val label = "$target over SSH"

    init {
        require(workspace.all { it.startsWith("/") }) { "SSH workspace folders must be absolute remote paths" }
    }

    internal fun argv(remoteCommand: String): List<String> = buildList {
        add("ssh")
        addAll(listOf("-o", "BatchMode=yes", "-o", "ControlMaster=auto", "-o", "ControlPersist=10m"))
        addAll(listOf("-o", "ControlPath=${File(controlDir, "%C").path}"))
        port?.let { addAll(listOf("-p", it.toString())) }
        identityFile?.let { addAll(listOf("-i", ExecBackends.expandHome(it))) }
        add(target)
        add(remoteCommand)
    }

    /** With [env], the exports come first on stdin (one base64 line, see [input]) so values stay out of the process list. */
    internal fun remoteCommand(script: String, cwd: String): String =
        if (env.isEmpty()) "cd ${shq(cwd)} && bash -lc ${shq(script)}"
        else "cd ${shq(cwd)} && bash -c ${shq(READ_EXPORTS)} ${shq(script)}"

    internal fun input(stdin: String?): String? = if (env.isEmpty()) stdin else {
        val exports = env.entries.joinToString("") { (k, v) -> "export $k=${shq(v)}; " }
        java.util.Base64.getEncoder().encodeToString(exports.toByteArray()) + "\n" + stdin.orEmpty()
    }

    override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: Duration, maxChars: Int): ExecResult {
        controlDir.mkdirs()
        val r = runProcess(argv(remoteCommand(script, cwd)), stdin = input(stdin), timeout = timeout, maxChars = maxChars)
        // ssh itself exits with 255 when it can't connect
        check(r.exitCode != 255) { "SSH to $target failed: ${r.output.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}" }
        return r
    }

    private companion object {
        const val READ_EXPORTS = """IFS= read -r e && eval "${'$'}(printf %s "${'$'}e" | base64 -d)" && unset e && exec bash -lc "${'$'}0""""
    }

    override suspend fun close() {
        super.close()
        runProcess(argv("").dropLast(2) + listOf("-O", "exit", target), timeout = 5.seconds)
    }
}
