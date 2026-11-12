package kinuvo.plugins.voice

import kinuvo.models.ModelDownload
import kinuvo.models.ModelFiles
import kinuvo.protocol.*
import kotlinx.coroutines.*
import java.io.File

class ModelStore(root: File, private val preferences: VoicePreferences) {
    val models = serverVoiceModels
    private val files = ModelFiles(root)
    var language: String
        get() = preferences.get("voice.language")?.takeIf { it in VOICE_LANGUAGES } ?: "en"
        set(value) { preferences.set("voice.language", value) }
    fun selected(kind: VoiceModelKind) = preferences.get("voice.${kind.name}")?.takeIf { id -> models.any { it.id == id && it.kind == kind } }
    fun select(kind: VoiceModelKind, id: String?) { preferences.set("voice.${kind.name}", id) }
    fun directory(model: VoiceModel): File = File(files.folder(model.download), model.archiveRoot ?: ".")
    fun acquire(model: VoiceModel) {
        check(model.id in installed()) { "Local voice model is missing or changed. Download it again in Server Voice settings." }
        files.acquire(model.download)
    }
    fun release(model: VoiceModel) = files.release(model.download)
    fun receipt(model: VoiceModel, folder: File) = files.receipt(model.download, folder)
    fun installed() = models.filter { files.installed(it.download) }.mapTo(mutableSetOf()) { it.id }
    suspend fun download(model: VoiceModel, progress: (Long) -> Unit) { check(model in models); files.download(model.download, progress) }
    suspend fun remove(model: VoiceModel) { check(model in models); files.remove(model.download) }
}

private val VoiceModel.download get() = ModelDownload(id, files, archiveRoot?.let { root -> SUPERTONIC.mapKeys { "$root/${it.key}" } } ?: files.associate { it.name to it.bytes },
    archiveRoot?.let { expected -> { staging -> unpack(File(staging, files.single().name), staging, expected) } })

private val SUPERTONIC = mapOf("duration_predictor.int8.onnx" to 3700147L, "text_encoder.int8.onnx" to 36416150L,
    "vector_estimator.int8.onnx" to 78400833L, "vocoder.int8.onnx" to 25991073L,
    "tts.json" to 8253L, "unicode_indexer.bin" to 262144L, "voice.bin" to 517168L)

private suspend fun unpack(archive: File, staging: File, expected: String) {
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

interface VoicePreferences { fun get(key: String): String?; fun set(key: String, value: String?) }
val VOICE_LANGUAGES = setOf("en", "es", "fr", "de", "it", "pt", "ja", "ko", "hi", "nl", "pl", "ru", "uk", "ar", "tr", "vi", "id", "sv", "bg", "hr", "cs", "da", "et", "fi", "el", "hu", "lv", "lt", "ro", "sk", "sl")
