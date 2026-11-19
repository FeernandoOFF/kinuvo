package kinuvo.plugins.cua

import kinuvo.protocol.device.*
import kinuvo.protocol.voice.ModelFile
import kinuvo.server.plugin.models.EngineFiles
import kinuvo.server.plugin.models.ModelDownload
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

internal fun cuaExecutable(platform: Platform) = if (platform == Platform.MacOS) "CuaDriver.app/Contents/MacOS/cua-driver" else if (platform == Platform.Windows) "cua-driver.exe" else "cua-driver"

fun cuaDriverEngine(home: File, platform: Platform = currentPlatform, arch: Arch = currentArch): EngineFiles? {
    val target = when (platform) {
        Platform.MacOS -> "darwin-universal"
        Platform.Linux, Platform.Windows -> {
            val cpu = when (arch) { Arch.X64 -> "x86_64"; Arch.Arm64 -> "arm64"; else -> return null }
            "${if (platform == Platform.Windows) "windows" else "linux"}-$cpu"
        }
        else -> return null
    }
    val (bytes, hash, executableBytes) = when (target) {
        "darwin-universal" -> Triple(69813387L, "d0d29e62a6f54454ad637f7b738c8b6dcff6538d7d03e505c87ad1f8c13685d2", 52955824L)
        "linux-x86_64" -> Triple(30224194L, "330ab1fa5548ba1025a33cffc686671e6ce76365aecec8d34d955f30a3c96bc7", 37362528L)
        "linux-arm64" -> Triple(28124037L, "f34641fc2a6ca9e946076001ccae5332b28b1309991e740f69fe210424c574a9", 32869280L)
        "windows-x86_64" -> Triple(32530720L, "c0b92cb56ec7863ca177a2813bf5e22bdea5772a56f5bc4e8be064eed34498d7", 33452880L)
        "windows-arm64" -> Triple(30330063L, "141887949d6b177c4b586b819871d5987c8af73f7d82c02ad7954f1a943db316", 28652880L)
        else -> return null
    }
    val name = "cua-driver-rs-0.54.0-$target"
    val archiveName = "$name.${if (platform == Platform.Windows) "zip" else "tar.gz"}"
    return EngineFiles(home, ModelDownload(name, listOf(ModelFile(archiveName,
        "https://github.com/trycua/cua/releases/download/cua-driver-rs-v0.54.0/$archiveName", bytes, hash)),
        mapOf("$name/${cuaExecutable(platform)}" to executableBytes)) { staging ->
        val archive = File(staging, archiveName)
        val entries = tar("-tf", archive.path).lineSequence().filter(String::isNotBlank).toList()
        check(entries.isNotEmpty() && entries.all {
            val path = it.replace('\\', '/')
            !path.startsWith('/') && ':' !in path && path.split('/').none { part -> part == ".." } && path.substringBefore('/') == name
        }) { "Unsafe cua-driver archive entry" }
        tar("-xf", archive.path, "-C", staging.path)
        Files.walk(staging.toPath()).use { paths -> paths.filter(Files::isSymbolicLink).forEach {
            check(it.toRealPath().startsWith(staging.toPath().toRealPath())) { "Unsafe cua-driver symlink" }
        } }
        archive.delete()
    })
}

private fun tarCommand() = if (currentPlatform == Platform.Windows) File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\tar.exe").path else "tar"

private suspend fun tar(vararg args: String): String = runInterruptible(Dispatchers.IO) {
    val process = ProcessBuilder(listOf(tarCommand()) + args).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    try {
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Cannot extract cua-driver" }
        output
    } finally { if (process.isAlive) process.destroyForcibly() }
}

class CuaDriverDownload(
    home: File,
    discover: () -> String? = ::findDriver,
    private val engine: EngineFiles? = cuaDriverEngine(home),
    private val download: suspend () -> String = {
        requireNotNull(engine).ensure()
        File(engine.folder, "${engine.download.id}/${cuaExecutable(currentPlatform)}").path
    },
    private val grant: (String) -> Unit = { path ->
        if (currentPlatform == Platform.MacOS) ProcessBuilder(path, "permissions", "grant")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var path = discover()
    @Volatile private var failure: String? = null
    private var job: Job? = null
    private var shown = false
    val ready get() = path != null || engine != null
    val message get() = failure ?: "Downloading cua-driver for Computer use. Try again in a minute."

    @Synchronized fun resolve(): String? {
        path?.let { return it }
        if (engine == null) return null
        if (failure != null && !shown) { shown = true; return null }
        if (job?.isActive != true) {
            failure = null; shown = false
            job = scope.launch {
                try {
                    val installed = engine.installed
                    val downloaded = download()
                    if (!installed) grant(downloaded)
                    path = downloaded
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = "cua-driver download failed: ${e.message}" }
            }
        }
        return null
    }

    fun close() { scope.cancel() }
}
