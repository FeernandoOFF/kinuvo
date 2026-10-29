package kinuvo.ui

import kinuvo.app.connection.Preferences
import kinuvo.app.voice.VoiceModelKind
import kinuvo.ui.voice.DesktopVoiceRepository
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Explicit network integration check; never downloads speech weights in routine test runs. */
class LocalVoiceDownloadTest {
    @Test fun damagedPayloadInvalidatesReceiptAndActiveModelCannotBeRemoved() = runBlocking {
        val root = Files.createTempDirectory("kinuvo-voice-integrity").toFile()
        val prefs = object : Preferences {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) {}
        }
        val repository = DesktopVoiceRepository(root, prefs)
        val model = repository.models.first()
        val folder = root.resolve(model.id).apply { mkdirs() }
        try {
            model.files.forEach { asset -> java.io.RandomAccessFile(folder.resolve(asset.name), "rw").use { it.setLength(asset.bytes) } }
            folder.resolve(".verified").writeText(repository.receipt(model, folder))
            assertTrue(model.id in repository.installed())
            repository.acquire(model)
            assertFailsWith<IllegalStateException> { repository.remove(model) }
            assertTrue(model.id in repository.installed())
            repository.release(model)
            val encoder = folder.resolve(model.files.first().name)
            encoder.setLastModified(encoder.lastModified() + 2000)
            assertFalse(model.id in repository.installed())
            folder.resolve(".verified").writeText(repository.receipt(model, folder))
            encoder.delete()
            assertFalse(model.id in repository.installed())
            repository.remove(model)
            assertFalse(folder.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun archiveRequiresEveryPayloadAtItsPinnedSizeEvenWithAReceipt() {
        val root = Files.createTempDirectory("kinuvo-voice-archive").toFile()
        val repository = DesktopVoiceRepository(root, object : Preferences {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) {}
        })
        val model = repository.models.single { it.id == "supertonic-3" }
        val folder = root.resolve(model.id).apply { mkdirs() }
        val payload = repository.directory(model).apply { mkdirs() }
        val sizes = mapOf("duration_predictor.int8.onnx" to 3700147L, "text_encoder.int8.onnx" to 36416150L,
            "vector_estimator.int8.onnx" to 78400833L, "vocoder.int8.onnx" to 25991073L,
            "tts.json" to 8253L, "unicode_indexer.bin" to 262144L, "voice.bin" to 517168L)
        fun receipt() { folder.resolve(".verified").writeText(repository.receipt(model, folder)) }
        try {
            receipt()
            assertFalse(model.id in repository.installed())
            sizes.forEach { (name, size) -> java.io.RandomAccessFile(payload.resolve(name), "rw").use { it.setLength(size) } }
            receipt()
            assertTrue(model.id in repository.installed())
            for ((name, size) in sizes) {
                val file = payload.resolve(name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(size - 1) }
                receipt()
                assertFalse(model.id in repository.installed(), name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
            }
            val voice = payload.resolve("voice.bin")
            voice.delete()
            val external = root.resolve("external.bin")
            java.io.RandomAccessFile(external, "rw").use { it.setLength(sizes.getValue("voice.bin")) }
            Files.createSymbolicLink(voice.toPath(), external.toPath())
            receipt()
            assertFalse(model.id in repository.installed())
        } finally { root.deleteRecursively() }
    }

    @Test fun pinnedDownloadsVerifyExtractAndRemoveAndCancellationCleansStaging() = runBlocking {
        if (System.getenv("KINUVO_TEST_LOCAL_VOICE_DOWNLOADS") != "1") return@runBlocking
        val root = Files.createTempDirectory("kinuvo-voice-model-test").toFile()
        val values = mutableMapOf<String, String>()
        val prefs = object : Preferences {
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        val repository = DesktopVoiceRepository(root, prefs)
        try {
            val tiny = repository.models.first { it.id == "whisper-tiny" }
            val began = CompletableDeferred<Unit>()
            val canceled = launch(Dispatchers.IO) { repository.download(tiny) { if (it > 65536) began.complete(Unit) } }
            began.await()
            canceled.cancelAndJoin()
            assertFalse(root.resolve("whisper-tiny.part").exists())
            assertTrue(repository.installed().isEmpty())
            for (model in repository.models.filter { it.id in setOf("whisper-tiny", "supertonic-3") }) {
                var progress = 0L
                repository.download(model) { progress = it }
                assertEquals(model.bytes, progress)
                assertTrue(model.id in repository.installed())
                assertNull(repository.selected(model.kind))
            }
            assertTrue(repository.directory(repository.models.last()).resolve("vocoder.int8.onnx").isFile)
            repository.select(VoiceModelKind.Transcription, tiny.id)
            assertEquals(tiny.id, repository.selected(VoiceModelKind.Transcription))
            repository.directory(tiny).resolve(tiny.files.first().name).delete()
            assertFalse(tiny.id in repository.installed())
            repository.download(tiny) {}
            assertTrue(tiny.id in repository.installed())
            repository.remove(tiny)
            assertFalse(tiny.id in repository.installed())
            // A disappeared selection must remain an explicit failure, not silently become system speech.
            assertEquals(tiny.id, repository.selected(VoiceModelKind.Transcription))
        } finally { root.deleteRecursively() }
    }
}
