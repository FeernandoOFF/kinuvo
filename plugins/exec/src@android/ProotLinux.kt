package kinuvo.plugins.exec

import android.content.Context
import android.os.Build
import android.system.Os
import kinuvo.server.plugin.PluginStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val ALPINE = "3.22"
private const val ALPINE_RELEASE = "3.22.2"

/** An Alpine rootfs run through proot, whose binaries ship as native libraries so Android lets them execute. */
class ProotLinux(private val nativeLibDir: File, home: File, internal val tmp: File) {
    private val linux = File(home, "linux")
    val rootfs = File(linux, "rootfs")
    private val ready = File(rootfs, ".kinuvo-ready")
    private val lock = Mutex()

    val available get() = File(nativeLibDir, "libproot.so").exists()

    suspend fun ensure() = lock.withLock {
        if (ready.exists()) return@withLock
        withContext(Dispatchers.IO) {
            rootfs.deleteRecursively()
            rootfs.mkdirs()
            val arch = when (Build.SUPPORTED_ABIS.first()) {
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "x86_64"
                else -> error("Linux environment is not available on ${Build.SUPPORTED_ABIS.first()}")
            }
            val url = "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE/releases/$arch/alpine-minirootfs-$ALPINE_RELEASE-$arch.tar.gz"
            URL(url).openStream().use { extractTarGz(it, rootfs) }
            File(rootfs, "etc/resolv.conf").apply { parentFile?.mkdirs(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
            File(rootfs, "tmp").mkdirs()
        }
        val install = runProcess(command(listOf("apk", "add", "--no-cache", "bash"), "/"), timeout = Duration.parse("10m"), env = environment())
        check(install.exitCode == 0) { "Could not set up the Linux environment: ${install.output}" }
        ready.writeText("ok")
    }

    fun environment(): Map<String, String> {
        tmp.mkdirs()
        return mapOf(
            "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").path,
            "PROOT_TMP_DIR" to tmp.path,
            "LD_LIBRARY_PATH" to nativeLibDir.path,
        )
    }

    /** Without [killOnExit], proot outlives [argv] until every process it started has exited. */
    fun command(argv: List<String>, cwd: String, killOnExit: Boolean = true, environment: Map<String, String> = emptyMap()): List<String> = buildList {
        add(File(nativeLibDir, "libproot.so").path)
        if (killOnExit) add("--kill-on-exit")
        addAll(listOf("--link2symlink", "-0", "-r", rootfs.path))
        listOf("/dev", "/proc", "/sys").forEach { addAll(listOf("-b", it)) }
        if (File("/storage").canRead()) addAll(listOf("-b", "/storage"))
        addAll(listOf("-w", cwd, "/usr/bin/env", "-i", "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp"))
        environment.forEach { (key, value) -> add("$key=$value") }
        addAll(argv)
    }

    fun guest(path: String) = File(rootfs, path.removePrefix("/"))
}

class ProotBackend(private val linux: ProotLinux, workspace: List<String>) : ExecBackend(workspace) {
    override val label = "this phone's Linux environment (Alpine via proot)"

    init { workspace.forEach { linux.guest(it).mkdirs() } }

    override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: Duration, maxChars: Int): ExecResult =
        runProcess(linux.command(listOf("bash", "-lc", script), cwd), stdin = stdin, timeout = timeout, env = linux.environment(), maxChars = maxChars)

    private val detached = mutableListOf<Process>()

    /** proot kills its processes when the script exits, so a background job gets its own proot that lives as long as the job. */
    override suspend fun spawnDetached(script: String, cwd: String): ExecResult = withContext(Dispatchers.IO) {
        val env = linux.environment()
        val out = File.createTempFile("kinuvo-bg", ".out", linux.tmp)
        val process = ProcessBuilder(linux.command(listOf("bash", "-lc", script), cwd, killOnExit = false))
            .apply { environment().putAll(env) }
            .redirectErrorStream(true).redirectOutput(out).start()
        synchronized(detached) { detached.removeAll { !it.isAlive }; detached += process }
        try {
            withTimeoutOrNull(30.seconds) {
                while (out.readText().lines().none { line -> line.isNotBlank() && line.trim().all(Char::isDigit) }) {
                    if (!process.isAlive) break
                    delay(50)
                }
            } ?: return@withContext ExecResult(-1, out.readText(), timedOut = true).also { process.destroyForcibly() }
            ExecResult(if (process.isAlive || process.exitValue() == 0) 0 else process.exitValue(), out.readText())
        } finally { out.delete() }
    }

    override suspend fun close() {
        super.close()
        synchronized(detached) { detached.toList().also { detached.clear() } }.forEach { it.destroyForcibly() }
    }
}

/** The phone's Linux environment, or null when this build does not ship proot. */
fun prootLinux(context: Context): ProotLinux? =
    ProotLinux(File(context.applicationInfo.nativeLibraryDir), File(context.filesDir, "Kinuvo"), File(context.cacheDir, "proot")).takeIf { it.available }

/** Exec on the phone's own Linux environment. */
fun prootExecPlugin(linux: ProotLinux, files: PluginStorage): ExecPlugin = ExecPlugin(files, host = { workspace ->
    linux.ensure()
    ProotBackend(linux, workspace.ifEmpty { listOf("/root") })
})

private fun extractTarGz(input: InputStream, dest: File) {
    val tar = GZIPInputStream(input.buffered())
    val header = ByteArray(512)
    var longName: String? = null
    val root = dest.canonicalPath
    fun target(name: String): File = File(dest, name).also {
        require(it.canonicalFile.path.startsWith(root)) { "Unsafe path in archive: $name" }
    }
    while (tar.readFully(header)) {
        if (header.all { it == 0.toByte() }) break
        val name = longName ?: (field(header, 345, 155).takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty() + field(header, 0, 100))
        longName = null
        val mode = field(header, 100, 8).trim().toIntOrNull(8) ?: 0b110100100
        val size = field(header, 124, 12).trim().toLongOrNull(8) ?: 0
        val type = header[156].toInt().toChar()
        val link = field(header, 157, 100)
        val clean = name.removePrefix("./").trim('/')
        when (type) {
            'L' -> { longName = String(readBytes(tar, size)).trimEnd('\u0000', '\n'); skipPadding(tar, size); continue }
            'x', 'g' -> { readBytes(tar, size); skipPadding(tar, size); continue }
            '5' -> if (clean.isNotEmpty()) target(clean).apply { mkdirs(); Os.chmod(path, mode or 0b111000000) }
            '2' -> target(clean).apply { parentFile?.mkdirs(); delete(); Os.symlink(link, path) }
            '1' -> target(clean).apply { parentFile?.mkdirs(); delete(); target(link.removePrefix("./").trim('/')).copyTo(this, overwrite = true) }
            '0', '\u0000', '7' -> target(clean).apply {
                parentFile?.mkdirs()
                delete()
                outputStream().use { out -> copyBytes(tar, out, size) }
                Os.chmod(path, mode or 0b110000000)
            }
        }
        if (type != '0' && type != '\u0000' && type != '7') skipBytes(tar, size)
        skipPadding(tar, size)
    }
}

private fun field(b: ByteArray, off: Int, len: Int): String {
    var end = off
    while (end < off + len && b[end] != 0.toByte()) end++
    return String(b, off, end - off)
}

private fun InputStream.readFully(b: ByteArray): Boolean {
    var n = 0
    while (n < b.size) {
        val r = read(b, n, b.size - n)
        if (r < 0) return false
        n += r
    }
    return true
}

private fun readBytes(i: InputStream, size: Long): ByteArray = ByteArray(size.toInt()).also { check(i.readFully(it)) { "Truncated archive" } }

private fun copyBytes(i: InputStream, out: OutputStream?, size: Long) {
    val buf = ByteArray(64 * 1024)
    var left = size
    while (left > 0) {
        val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        check(r >= 0) { "Truncated archive" }
        out?.write(buf, 0, r)
        left -= r
    }
}

private fun skipBytes(i: InputStream, size: Long) = copyBytes(i, null, size)

private fun skipPadding(i: InputStream, size: Long) {
    val pad = (512 - size % 512) % 512
    if (pad > 0) skipBytes(i, pad)
}
