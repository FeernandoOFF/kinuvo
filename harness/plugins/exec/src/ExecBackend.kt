package kinuvo.harness.exec

import kinuvo.harness.core.InstructionSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)

data class BackgroundProcess(val id: String, val pid: String, val log: String, val command: String) {
    internal val exitFile get() = "$log.exit"
}

data class BackgroundStatus(val running: Boolean, val exit: String?, val output: String)

/**
 * Where an Agent's shell and file tools run. File operations are shell scripts on every backend,
 * so host, container and SSH only differ in how a script is executed.
 */
abstract class ExecBackend(
    /** Workspace folders as paths on the backend (e.g. `/workspace/app` inside a container). */
    val roots: List<String>,
) {
    abstract val label: String

    /** Where the shell and file tools run, as described to the model. */
    val instructions get() = InstructionSection(
        "Workspace",
        "Your shell and file tools run on $label. Workspace folders:\n" +
            roots.joinToString("\n") { "- $it" } + "\nRelative paths resolve against the first folder.",
    )

    /** Output past [maxChars] keeps only its start and end. */
    protected abstract suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: Duration, maxChars: Int = 30_000): ExecResult

    private val background = LinkedHashMap<String, BackgroundProcess>()
    private val tag = java.util.UUID.randomUUID().toString().take(8)

    /** Kills this backend's background processes; subclasses that override must call super. */
    open suspend fun close() {
        val all = synchronized(background) { background.values.toList().also { background.clear() } }
        all.forEach { runCatching { kill(it) } }
    }

    init {
        require(roots.isNotEmpty()) { "Exec backends need at least one workspace folder" }
    }

    suspend fun run(command: String, cwd: String?, timeout: Duration): ExecResult =
        execScript(command, cwd?.let(::resolve) ?: roots.first(), null, timeout)

    /** Starts [command] detached in its own process group; output goes to a log file on the backend. */
    suspend fun startBackground(command: String, cwd: String?): BackgroundProcess {
        val id = synchronized(background) { "bg${background.size + 1}" }
        val log = "/tmp/kinuvo-bg-$tag-$id.log"
        val wrapper = "bash -lc \"\$1\"; echo \$? > \"\$2\""
        val script = "set -m; rm -f ${shq("$log.exit")}; nohup bash -c ${shq(wrapper)} _ ${shq(command)} ${shq("$log.exit")} > ${shq(log)} 2>&1 < /dev/null & echo \$!"
        // Background tasks only last 30sec
        val r = execScript(script, cwd?.let(::resolve) ?: roots.first(), null, Duration.parse("30s"))
        val pid = r.output.lines().lastOrNull { it.isNotBlank() }?.trim()
        check(r.exitCode == 0 && pid?.all(Char::isDigit) == true) { r.output.ifBlank { "Could not start background process" } }
        return BackgroundProcess(id, pid, log, command).also { synchronized(background) { background[id] = it } }
    }

    fun backgroundProcess(id: String): BackgroundProcess = synchronized(background) {
        background[id] ?: throw IllegalArgumentException("No background process '$id' (started: ${background.keys.joinToString().ifEmpty { "none" }})")
    }

    suspend fun status(p: BackgroundProcess, tailChars: Int): BackgroundStatus {
        val script = "s=stopped; kill -0 ${p.pid} 2>/dev/null && [ ! -f ${shq(p.exitFile)} ] && s=running; " +
            "echo \"\$s \$(cat ${shq(p.exitFile)} 2>/dev/null)\"; tail -c $tailChars ${shq(p.log)} 2>/dev/null"
        val r = execScript(script, roots.first(), null, Duration.parse("30s"))
        val (state, exit) = r.output.substringBefore('\n').trim().split(" ", limit = 2).let { it[0] to it.getOrNull(1) }
        return BackgroundStatus(state == "running", exit?.takeIf { it.isNotBlank() }, r.output.substringAfter('\n', ""))
    }

    suspend fun kill(p: BackgroundProcess) {
        val exit = shq(p.exitFile)
        execScript(
            "kill -TERM -- -${p.pid} 2>/dev/null || kill -TERM ${p.pid} 2>/dev/null; [ -f $exit ] || echo killed > $exit",
            roots.first(), null, Duration.parse("30s"),
        )
    }

    /**
     * The first [maxBytes] of [path], refusing symlinks that lead out of the Workspace. With [whole], the whole file,
     * failing when it's larger, instead of the start and end of a long one.
     */
    suspend fun read(path: String, maxBytes: Int = 2_000_000, whole: Boolean = false): String {
        val p = resolve(path)
        val real = roots.joinToString(" ") { "\"$(readlink -f ${shq(it)})\"" }
        // Encode whole reads so the display collector cannot change line endings or clip file content silently.
        val read = if (whole) "head -c ${maxBytes + 1} -- \"\$p\" | base64" else "head -c $maxBytes -- \"\$p\""
        val script = "set -o pipefail; p=$(readlink -f ${shq(p)}) || exit 1; ok=; for r in $real; do case \"\$p\" in \"\$r\"|\"\$r\"/*) ok=1;; esac; done; " +
            "[ -n \"\$ok\" ] || { echo ${shq(p)}' links outside the workspace'; exit 1; }; $read"
        val r = execScript(script, roots.first(), null, Duration.parse("30s"), if (whole) 2 * maxBytes + 16 else 30_000)
        check(r.exitCode == 0) { r.output.ifBlank { "Cannot read $p" } }
        if (!whole) return r.output
        val bytes = Base64.getDecoder().decode(r.output.filterNot { it == '\n' || it == '\r' })
        check(bytes.size <= maxBytes) { "$p is larger than $maxBytes bytes" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    suspend fun write(path: String, content: String) {
        val p = resolve(path)
        val dir = p.substringBeforeLast('/', "")
        val mkdir = if (dir.isNotEmpty()) "mkdir -p -- ${shq(dir)} && " else ""
        val r = execScript("${mkdir}cat > ${shq(p)}", roots.first(), content, Duration.parse("30s"))
        check(r.exitCode == 0) { r.output.ifBlank { "Cannot write $p" } }
    }

    /** Files under [path] (skipping .git, build and node_modules), optionally filtered by [glob] on relative paths. */
    suspend fun list(path: String?, glob: String?, limit: Int = 500): List<String> {
        val base = path?.let(::resolve) ?: roots.first()
        val prune = "\\( -name .git -o -name node_modules -o -name build -o -name .kotlin \\) -prune -o"
        val r = execScript("find ${shq(base)} -mindepth 1 $prune -type f -print 2>/dev/null | head -n 20000", roots.first(), null, Duration.parse("30s"))
        val matcher = glob?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        return r.output.lineSequence().filter { it.isNotBlank() }
            .map { it.removePrefix("$base/") }
            .filter { matcher == null || matcher.matches(Paths.get(it)) }
            .sorted().take(limit).toList()
    }

    /** Absolute, normalized path inside a Workspace folder; relative paths are resolved against the first one. */
    fun resolve(path: String): String {
        val abs = if (path.startsWith("/")) path else "${roots.first()}/$path"
        val normalized = Paths.get(abs).normalize().toString()
        require(roots.any { normalized == it || normalized.startsWith("$it/") }) {
            "Path $path is outside the workspace (${roots.joinToString()})"
        }
        return normalized
    }

    companion object {
        fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** Runs [argv], feeding [stdin], killing it after [timeout]; keeps the head and tail of long output. */
internal suspend fun runProcess(
    argv: List<String>,
    dir: File? = null,
    stdin: String? = null,
    timeout: Duration,
    env: Map<String, String> = emptyMap(),
    maxChars: Int = 30_000,
): ExecResult = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(argv).redirectErrorStream(true).apply {
        dir?.let(::directory)
        environment().putAll(env)
    }.start()
    try {
        val writer = Thread {
            runCatching { process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } } }
        }.apply { start() }
        val output = CappedOutput(maxChars)
        val reader = Thread { process.inputStream.bufferedReader().use { r -> r.forEachLine(output::appendLine) } }.apply { start() }
        val finished = runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        if (!finished) process.descendants().forEach { it.destroyForcibly() }.also { process.destroyForcibly() }
        reader.join(2_000)
        writer.join(500)
        ExecResult(if (finished) process.exitValue() else -1, output.text(), timedOut = !finished)
    } finally {
        if (process.isAlive) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}

private class CappedOutput(private val maxChars: Int) {
    private val head = StringBuilder()
    private val tail = ArrayDeque<String>()
    private var tailChars = 0
    private var dropped = 0

    @Synchronized fun appendLine(line: String) {
        if (head.length + line.length < maxChars / 2) { head.append(line).append('\n'); return }
        tail.addLast(line); tailChars += line.length + 1
        while (tailChars > maxChars / 2 && tail.size > 1) { tailChars -= tail.removeFirst().length + 1; dropped++ }
    }

    @Synchronized fun text(): String = buildString {
        append(head)
        if (dropped > 0) append("\n…[$dropped lines omitted]…\n\n")
        tail.forEach { append(it).append('\n') }
    }.trimEnd('\n')
}
