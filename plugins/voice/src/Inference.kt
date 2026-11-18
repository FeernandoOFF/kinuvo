package kinuvo.plugins.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.jar.JarFile

@OptIn(InternalCoroutinesApi::class)
class Inference(private val executable: File) {
    val unavailableReason: String? get() {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        val x64 = arch in setOf("amd64", "x86_64")
        val platform = os.startsWith("Mac") && (x64 || arch in setOf("arm64", "aarch64")) ||
            (os.startsWith("Linux") || os.startsWith("Windows")) && x64
        return when {
            !platform -> "Downloaded voice isn't available on this Server's platform. Linux arm64 is unsupported."
            !executable.isFile || !executable.canExecute() -> "Voice worker missing from this Server build."
            else -> null
        }
    }
    val supported get() = unavailableReason == null
    private val slots = Semaphore(1)
    suspend fun run(operation: String, directory: File, language: String, variant: String, input: ByteArray, libraries: File? = null): ByteArray {
        check(supported) { unavailableReason ?: "Voice inference unavailable" }
        return slots.withPermit { withTimeout(120_000) { withContext(Dispatchers.IO) {
            val args = mutableListOf(executable.path)
            libraries?.let { args += it.absolutePath }
            args += listOf(operation, directory.path, language)
            if (operation == "transcribe") args += variant
            val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) process.destroyForcibly() }
            try {
                coroutineScope {
                    val writer = launch(Dispatchers.IO) { process.outputStream.use { it.write(input) } }
                    val output = runInterruptible { process.inputStream.use { stream ->
                        val bytes = stream.readNBytes(8 * 1024 * 1024 + 1)
                        check(bytes.size <= 8 * 1024 * 1024) { "Voice output too large" }; bytes
                    } }
                    writer.join()
                    runInterruptible { check(process.waitFor() == 0) { "Voice inference failed" } }
                    output
                }
            } finally {
                process.destroyForcibly()
                withContext(NonCancellable) { runInterruptible { process.waitFor() } }
                cancellation.dispose()
            }
        } } }
    }
    companion object {
        fun bundled(): File {
            val worker = if (System.getProperty("os.name").startsWith("Mac")) "MacOS/KinuvoVoiceInference"
                else if (System.getProperty("os.name").startsWith("Windows")) "windows-x64/KinuvoVoiceInference.exe"
                else "linux-x64/KinuvoVoiceInference"
            System.getenv("KINUVO_VOICE_WORKER")?.let { return File(it) }
            val archive = File(System.getProperty("java.class.path", ""))
            if (archive.isFile && archive.extension == "jar") {
                JarFile(archive).use { jar ->
                    if (jar.getJarEntry("server-voice/$worker") != null) {
                        val root = Files.createTempDirectory("kinuvo-voice-").toFile()
                        Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
                        jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("server-voice/") }.forEach { entry ->
                            val file = File(root, entry.name.removePrefix("server-voice/")); file.parentFile.mkdirs()
                            jar.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                        }
                        return File(root, worker).apply { setExecutable(true, true) }
                    }
                }
            }
            val resources = System.getProperty("compose.application.resources.dir")
            if (resources != null) return File(resources, "voice-worker/$worker")
            val root = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile }
            return File(root ?: File("."), "build/voice-worker/$worker")
        }
        fun pcmToFloat(bytes: ByteArray): ByteArray {
            require(bytes.isNotEmpty() && bytes.size <= 960000 && bytes.size % 2 == 0) { "Expected at most 30 seconds of 16 kHz mono PCM16" }
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val output = ByteBuffer.allocate(bytes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) output.putFloat(input.short / 32768f)
            return output.array()
        }
    }
}
