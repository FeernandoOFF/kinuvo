package kinuvo.models

import kinuvo.protocol.ModelFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.security.MessageDigest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class EngineFilesTest {
    @Test fun downloadsAndVerifiesEngines() = runBlocking {
        val home = System.getenv("KINUVO_TEST_ENGINE_HOME")?.let(::File) ?: return@runBlocking
        for (engine in listOf(liteRtEngine(home), voiceEngine(home))) {
            var done = 0L
            engine.ensure { done = it }
            assertEquals(engine.download.bytes, done)
            assertTrue(engine.installed)
            engine.download.payload.forEach { (name, size) -> assertEquals(size, File(engine.folder, name).length()) }
            println("Verified ${engine.download.id}: ${engine.folder}")
        }
    }

    @Test fun concurrentSetupReusesVerifiedPayloadAndDetectsDamage() = runBlocking {
        val home = Files.createTempDirectory("engine-receipt").toFile()
        try {
            val bytes = "engine payload".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val engine = EngineFiles(home, ModelDownload("test-1", listOf(ModelFile("native", "https://127.0.0.1:1/native", bytes.size.toLong(), hash))))
            File(home, "engines/test-1.part/native").apply { parentFile.mkdirs(); writeBytes(bytes) }
            listOf(async { engine.ensure() }, async { engine.ensure() }).awaitAll()
            assertTrue(engine.installed)
            engine.ensure { assertEquals(bytes.size.toLong(), it) }
            File(engine.folder, "native").appendText("damage")
            assertFalse(engine.installed)
        } finally { home.deleteRecursively() }
    }

    @Test fun missingEngineFailsClearlyOffline() = runBlocking {
        val home = Files.createTempDirectory("engine-offline").toFile()
        try {
            val engine = EngineFiles(home, ModelDownload("offline-1", listOf(ModelFile("native", "https://127.0.0.1:1/native", 1, "invalid"))))
            val failure = assertFailsWith<IllegalStateException> { engine.ensure() }
            assertContains(failure.message.orEmpty(), "Connect to the internet and retry")
            assertFalse(engine.installed)
        } finally { home.deleteRecursively() }
    }
}
