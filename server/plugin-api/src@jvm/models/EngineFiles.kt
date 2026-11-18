package kinuvo.server.plugin.models

import kinuvo.protocol.voice.ModelFile
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

fun voiceEngine(home: File): EngineFiles {
    val os = System.getProperty("os.name")
    val platform = when {
        os.startsWith("Linux") -> "linux-x64-shared-lib"
        os.startsWith("Windows") -> "win-x64-shared-MT-Release-lib"
        else -> "osx-universal2-shared-lib"
    }
    val (size, hash, libraries) = when {
        os.startsWith("Linux") -> Triple(9816899L, "3892d184be41027e18165e67f549cd4e4cdd8dcd73ac5579e97afd55e14e30b6",
            mapOf("libsherpa-onnx-c-api.so" to 5124192L, "libonnxruntime.so" to 27026609L))
        os.startsWith("Windows") -> Triple(8032957L, "b8eedf41bd6d3779218887b48367bb7a3ece5aaa7667f01f69ee823a12b0a9e7",
            mapOf("sherpa-onnx-c-api.dll" to 4605952L, "onnxruntime.dll" to 17799168L, "onnxruntime_providers_shared.dll" to 104960L))
        else -> Triple(18819320L, "c1cca5b4a1867543e27d42fbea513983e73f629d0aa53b43b51555a71d2108d9",
            mapOf("libsherpa-onnx-c-api.dylib" to 8743968L, "libonnxruntime.dylib" to 61117136L))
    }
    val name = "sherpa-onnx-v1.13.8-$platform"
    val id = if (os.startsWith("Mac")) "sherpa-onnx-1.13.8-macos-universal2" else "sherpa-onnx-1.13.8-$platform"
    return EngineFiles(home, ModelDownload(id, listOf(ModelFile("engine.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/$name.tar.bz2", size, hash)), libraries) { staging ->
        val archive = File(staging, "engine.tar.bz2")
        for (library in libraries.keys) {
            val process = ProcessBuilder("tar", "-xOjf", archive.path, "$name/lib/$library")
                .redirectOutput(File(staging, library)).start()
            try { runInterruptible { check(process.waitFor() == 0) { "Cannot extract voice engine" } } }
            finally { if (process.isAlive) process.destroyForcibly() }
        }
        archive.delete()
    })
}
