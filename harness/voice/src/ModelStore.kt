package kinuvo.voice

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class ModelStore(private val root: File, private val preferences: VoicePreferences) {
    val models = serverVoiceModels
    var language: String
        get() = preferences.get("voice.language")?.takeIf { it in VOICE_LANGUAGES } ?: "en"
        set(value) { preferences.set("voice.language", value) }
    fun selected(kind: VoiceModelKind) = preferences.get("voice.${kind.name}")?.takeIf { id -> models.any { it.id == id && it.kind == kind } }
    fun select(kind: VoiceModelKind, id: String?) { preferences.set("voice.${kind.name}", id) }
    fun directory(model: VoiceModel): File = File(File(root, model.id), model.archiveRoot ?: ".")
    private val users = mutableMapOf<String, Int>()
    @Synchronized fun acquire(model: VoiceModel) {
        check(model.id in installed()) { "Local voice model is missing or changed. Download it again in Server Voice settings." }
        users[model.id] = (users[model.id] ?: 0) + 1
    }
    @Synchronized fun release(model: VoiceModel) {
        val count = users[model.id] ?: return
        if (count == 1) users.remove(model.id) else users[model.id] = count - 1
    }
    private fun payload(folder: File) = folder.walkTopDown().filter { it.isFile && it.name != ".verified" }
        .sortedBy { it.relativeTo(folder).invariantSeparatorsPath }.toList()
    // Download hashes establish integrity; metadata detects subsequent damage without hashing weights on the UI thread.
    fun receipt(model: VoiceModel, folder: File): String =
        model.files.joinToString("\n") { it.sha256 } + "\n" + payload(folder).joinToString("\n") {
            "${it.relativeTo(folder).invariantSeparatorsPath}\t${it.length()}\t${Files.getLastModifiedTime(it.toPath())}"
        }
    private fun validPayload(model: VoiceModel, folder: File): Boolean {
        val required = if (model.archiveRoot == null) model.files.associate { it.name to it.bytes } else mapOf(
            "duration_predictor.int8.onnx" to 3700147L, "text_encoder.int8.onnx" to 36416150L,
            "vector_estimator.int8.onnx" to 78400833L, "vocoder.int8.onnx" to 25991073L,
            "tts.json" to 8253L, "unicode_indexer.bin" to 262144L, "voice.bin" to 517168L)
        val directory = File(folder, model.archiveRoot ?: ".")
        return !Files.isSymbolicLink(folder.toPath()) && !Files.isSymbolicLink(directory.toPath()) && required.all { (name, bytes) ->
            val file = File(directory, name)
            !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() == bytes
        }
    }
    @Synchronized fun installed() = models.filter { model -> runCatching {
        val folder = File(root, model.id)
        validPayload(model, folder) && File(folder, ".verified").readText() == receipt(model, folder)
    }.getOrDefault(false) }.mapTo(mutableSetOf()) { it.id }

    private val downloads = Mutex()

    suspend fun download(model: VoiceModel, progress: (Long) -> Unit) = downloads.withLock { withContext(Dispatchers.IO) {
        check(model in models)
        root.mkdirs()
        val staging = File(root, "${model.id}.part")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create model download folder" }
        var received = 0L
        try {
            for (asset in model.files) {
                val connection = URI(asset.url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = true
                try {
                    check(connection.responseCode == 200) { "Download returned HTTP ${connection.responseCode}. Retry." }
                    check(connection.url.protocol == "https") { "Insecure download redirect" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    connection.inputStream.use { input -> File(staging, asset.name).outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            check(count <= asset.bytes) { "Model exceeds its expected size" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            received += n
                            progress(received)
                        }
                    } }
                    check(count == asset.bytes && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256) { "Model verification failed. Retry." }
                } finally { connection.disconnect() }
            }
            model.archiveRoot?.let { expected ->
                val archive = File(staging, model.files.single().name)
                val listing = ProcessBuilder("/usr/bin/tar", "-tjf", archive.path).start()
                val names = listing.inputStream.bufferedReader().readLines()
                check(listing.waitFor() == 0 && names.isNotEmpty() && names.all { name ->
                    (name == "$expected/" || name.startsWith("$expected/")) && name.split('/').none { it == ".." }
                }) { "Unexpected model archive layout" }
                // Only regular files/directories are accepted, even in a verified upstream archive.
                val types = ProcessBuilder("/usr/bin/tar", "-tvjf", archive.path).start()
                val entries = types.inputStream.bufferedReader().readLines()
                check(types.waitFor() == 0 && entries.all { it.firstOrNull() in listOf('-', 'd') }) { "Unsupported archive entry" }
                val unpack = ProcessBuilder("/usr/bin/tar", "-xjf", archive.path, "-C", staging.path).start()
                try { runInterruptible { check(unpack.waitFor() == 0) { "Cannot unpack model" } } }
                finally { if (unpack.isAlive) unpack.destroyForcibly() }
                archive.delete()
            }
            currentCoroutineContext().ensureActive()
            check(validPayload(model, staging)) { "Model payload is incomplete or has unexpected sizes. Retry." }
            File(staging, ".verified").writeText(receipt(model, staging))
            val destination = File(root, model.id)
            synchronized(this@ModelStore) {
                check(model.id !in users) { "Model is in use. Stop Dictation and Voice chat before retrying." }
                check(model.id !in installed()) { "Model already installed" }
                check(!destination.exists() || destination.deleteRecursively()) { "Cannot replace damaged model" }
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            Unit
        } finally { staging.deleteRecursively() }
    } }

    suspend fun remove(model: VoiceModel) = withContext(Dispatchers.IO) {
        check(model in models)
        synchronized(this@ModelStore) {
            check(model.id !in users) { "Model is in use. Stop Dictation and Voice chat before removing it." }
            check(File(root, model.id).deleteRecursively()) { "Cannot remove model" }
        }
    }
}

interface VoicePreferences { fun get(key: String): String?; fun set(key: String, value: String?) }
val VOICE_LANGUAGES = setOf("en", "es", "fr", "de", "it", "pt", "ja", "ko", "hi", "nl", "pl", "ru", "uk", "ar", "tr", "vi", "id", "sv", "bg", "hr", "cs", "da", "et", "fi", "el", "hu", "lv", "lt", "ro", "sk", "sl")
