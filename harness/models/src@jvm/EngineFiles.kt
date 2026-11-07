package kinuvo.harness.models

import kinuvo.protocol.ModelFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipFile

/** Versioned native engines use the same verified, resumable store as model weights. */
class EngineFiles(home: File, val download: ModelDownload) {
    private val lock = Mutex()
    private val files = ModelFiles(File(home, "engines"))
    val folder get() = files.folder(download)
    val installed get() = files.installed(download)
    suspend fun ensure(progress: (Long) -> Unit = {}) = lock.withLock {
        if (installed) { progress(download.bytes); return@withLock }
        try { files.download(download, progress) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException("Cannot download ${download.id}. Connect to the internet and retry: ${e.message}", e) }
    }
}

fun liteRtEngine(home: File): EngineFiles {
    val arch = when (System.getProperty("os.arch")) { "arm64", "aarch64" -> "aarch64"; else -> "x86_64" }
    val os = System.getProperty("os.name").let { when { it.startsWith("Mac") -> "darwin"; it.startsWith("Windows") -> "windows"; else -> "linux" } }
    val library = if (os == "windows") "litertlm_jni.dll" else "liblitertlm_jni.so"
    val size = mapOf("darwin-aarch64" to 67880736L, "linux-aarch64" to 130436560L, "linux-x86_64" to 133296184L, "windows-x86_64" to 52910080L)
    val platform = "$os-$arch"
    return EngineFiles(home, ModelDownload("litertlm-0.19.0-$platform", listOf(ModelFile("engine.jar",
        "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-jvm/0.19.0/litertlm-jvm-0.19.0.jar",
        134218389, "f3fe7af81aa570f46a2566ee0cd7d1f2f564a312a29428afb885d46e74c1d15d")),
        mapOf(library to requireNotNull(size[platform]) { "Unsupported LiteRT engine platform: $platform" })) { staging ->
        val archive = File(staging, "engine.jar")
        ZipFile(archive).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry("com/google/ai/edge/litertlm/jni/$platform/$library"))).use { input ->
                File(staging, library).outputStream().use { input.copyTo(it) }
            }
        }
        archive.delete()
    })
}

fun voiceEngine(home: File) = EngineFiles(home, ModelDownload("sherpa-onnx-1.13.8-macos-universal2", listOf(ModelFile("engine.tar.bz2",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-osx-universal2-shared-lib.tar.bz2",
    18819320, "c1cca5b4a1867543e27d42fbea513983e73f629d0aa53b43b51555a71d2108d9")),
    mapOf("libsherpa-onnx-c-api.dylib" to 8743968L, "libonnxruntime.dylib" to 61117136L)) { staging ->
    val archive = File(staging, "engine.tar.bz2")
    // Extract only known regular files from the hash-pinned archive, never archive paths or links.
    for (name in listOf("libsherpa-onnx-c-api.dylib", "libonnxruntime.dylib")) {
        val process = ProcessBuilder("/usr/bin/tar", "-xOjf", archive.path,
            "sherpa-onnx-v1.13.8-osx-universal2-shared-lib/lib/$name").redirectOutput(File(staging, name)).start()
        try { runInterruptible { check(process.waitFor() == 0) { "Cannot extract voice engine" } } }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
    archive.delete()
})
