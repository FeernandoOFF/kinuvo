package kinuvo.harness.models

import kinuvo.protocol.ModelFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A model's pinned [files]. Once installed, every path in [payload] exists at its size, e.g. after [unpack] extracts an
 * archive in the staging folder.
 */
class ModelDownload(
    val id: String,
    val files: List<ModelFile>,
    val payload: Map<String, Long> = files.associate { it.name to it.bytes },
    val unpack: (suspend (staging: File) -> Unit)? = null,
) {
    val bytes get() = files.sumOf { it.bytes }
}

/**
 * Models downloaded into [root], one folder each. Downloads are checked against pinned sizes and SHA-256 hashes, staged
 * and moved into place atomically; an interrupted one resumes where it stopped, while canceling discards it.
 */
class ModelFiles(private val root: File) {
    private val users = mutableMapOf<String, Int>()
    private val downloads = Mutex()

    fun folder(model: ModelDownload) = File(root, model.id)

    private fun payload(folder: File) = folder.walkTopDown().filter { it.isFile && it.name != ".verified" }
        .sortedBy { it.relativeTo(folder).invariantSeparatorsPath }.toList()

    // Download hashes establish integrity; metadata detects later damage without hashing weights on every check.
    fun receipt(model: ModelDownload, folder: File): String =
        model.files.joinToString("\n") { it.sha256 } + "\n" + payload(folder).joinToString("\n") {
            "${it.relativeTo(folder).invariantSeparatorsPath}\t${it.length()}\t${Files.getLastModifiedTime(it.toPath())}"
        }

    private fun validPayload(model: ModelDownload, folder: File) = !Files.isSymbolicLink(folder.toPath()) && model.payload.all { (path, bytes) ->
        val file = File(folder, path)
        generateSequence(file) { it.parentFile }.takeWhile { it != folder }.none { Files.isSymbolicLink(it.toPath()) } && file.isFile && file.length() == bytes
    }

    @Synchronized fun installed(model: ModelDownload) = runCatching {
        val folder = folder(model)
        validPayload(model, folder) && File(folder, ".verified").readText() == receipt(model, folder)
    }.getOrDefault(false)

    @Synchronized fun acquire(model: ModelDownload) {
        check(installed(model)) { "The model is missing or changed. Download it again." }
        users[model.id] = (users[model.id] ?: 0) + 1
    }

    @Synchronized fun release(model: ModelDownload) {
        val count = users[model.id] ?: return
        if (count == 1) users.remove(model.id) else users[model.id] = count - 1
    }

    suspend fun download(model: ModelDownload, progress: (Long) -> Unit) = downloads.withLock { withContext(Dispatchers.IO) {
        root.mkdirs()
        val staging = File(root, "${model.id}.part")
        staging.mkdirs()
        check(staging.isDirectory) { "Cannot create model download folder" }
        val partial = model.files.sumOf { File(staging, it.name).length().coerceAtMost(it.bytes) }
        val needed = model.bytes - partial
        check(root.usableSpace >= needed + SPARE) { "Not enough free space: the download needs ${gigabytes(needed)}" }
        progress(partial)
        try {
            var done = 0L
            for (asset in model.files) {
                val base = done
                fetch(asset, File(staging, asset.name)) { progress(base + it) }
                done += asset.bytes
            }
            currentCoroutineContext().ensureActive()
            model.unpack?.invoke(staging)
            currentCoroutineContext().ensureActive()
            check(validPayload(model, staging)) { "Model payload is incomplete or has unexpected sizes. Retry." }
            File(staging, ".verified").writeText(receipt(model, staging))
            val destination = folder(model)
            synchronized(this@ModelFiles) {
                check(model.id !in users) { "The model is in use. Try again once it's idle." }
                check(!installed(model)) { "Model already installed" }
                check(!destination.exists() || destination.deleteRecursively()) { "Cannot replace damaged model" }
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            Unit
        } catch (e: CancellationException) {
            staging.deleteRecursively()
            throw e
        }
    } }

    /** Fetches [asset] into [file], resuming a partial one; [progress] gets its bytes so far. */
    private suspend fun fetch(asset: ModelFile, file: File, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = if (file.length() in 1..asset.bytes) file.length() else 0L.also { file.delete() }
        if (count > 0) file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (count < asset.bytes) {
            val connection = URI(asset.url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            if (count > 0) connection.setRequestProperty("Range", "bytes=$count-")
            try {
                val code = connection.responseCode
                check(code == 200 || code == 206 && count > 0) { "Download returned HTTP $code. Retry." }
                check(connection.url.protocol == "https") { "Insecure download redirect" }
                if (code == 200 && count > 0) { count = 0; digest.reset() }
                connection.inputStream.use { input -> FileOutputStream(file, count > 0).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        check(count <= asset.bytes) { file.delete(); "Model exceeds its expected size" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        progress(count)
                    }
                } }
            } finally { connection.disconnect() }
        }
        progress(count)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (count != asset.bytes || hash != asset.sha256) { file.delete(); error("Model verification failed. Retry.") }
    }

    suspend fun remove(model: ModelDownload) = withContext(Dispatchers.IO) {
        synchronized(this@ModelFiles) {
            check(model.id !in users) { "The model is in use. Try again once it's idle." }
            check(folder(model).deleteRecursively()) { "Cannot remove model" }
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val SPARE = 256L * 1024 * 1024
    }
}

fun gigabytes(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
