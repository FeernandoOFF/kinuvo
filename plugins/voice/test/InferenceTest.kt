package kinuvo.plugins.voice

import kinuvo.testing.harness.TemporaryDirectories
import java.net.URLClassLoader
import kotlinx.coroutines.*
import kotlin.test.*

class InferenceTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    @Test fun cancellationKillsAndReapsWorkerBeforeReturning() = runBlocking {
        if (!java.io.File("/bin/sh").canExecute() || !Inference(java.io.File("/bin/sh")).supported) return@runBlocking
        val root = temporaryDirectories.create("voice-cancel").toFile()
        val pid = root.resolve("pid")
        val worker = root.resolve("worker").apply {
            writeText("#!/bin/sh\necho $$ > '${pid.path}'\nexec /bin/sleep 30\n")
            setExecutable(true)
        }
        try {
            val job = launch { Inference(worker).run("synthesize", root, "en", "tiny", byteArrayOf()) }
            withTimeout(5000) { while (!pid.exists() || pid.length() == 0L) delay(10) }
            val handle = ProcessHandle.of(pid.readText().trim().toLong()).orElseThrow()
            withTimeout(5000) { job.cancelAndJoin() }
            assertFalse(handle.isAlive)
        } finally { root.deleteRecursively() }
    }
    @Test fun extractsIndexedWorkerAndNestedResourcesWithoutChangingBytes() {
        val root = temporaryDirectories.create("voice-resources").toFile()
        val worker = "MacOS/KinuvoVoiceInference"
        root.resolve("server-voice/$worker").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 1, 2, 127)) }
        root.resolve("server-voice/Resources/license.txt").apply { parentFile.mkdirs(); writeText("license") }
        root.resolve("server-voice/files.list").writeText("server-voice/$worker\nserver-voice/Resources/license.txt\n")
        URLClassLoader(arrayOf(root.toURI().toURL()), null).use { loader ->
            val extracted = assertNotNull(extractBundledVoiceWorker(loader, worker))
            val bundle = extracted.parentFile.parentFile
            try {
                assertTrue(extracted.canExecute())
                assertContentEquals(byteArrayOf(0, 1, 2, 127), extracted.readBytes())
                assertEquals("license", bundle.resolve("Resources/license.txt").readText())
                assertNull(extractBundledVoiceWorker(loader, "linux-x64/missing"))
            } finally { bundle.deleteRecursively() }
        }
    }

    @Test fun rejectsIndexedResourcesOutsideTheBundle() {
        val root = temporaryDirectories.create("voice-resources").toFile()
        val worker = "MacOS/KinuvoVoiceInference"
        root.resolve("server-voice/$worker").apply { parentFile.mkdirs(); writeText("worker") }
        root.resolve("server-voice/files.list").writeText("server-voice/$worker\nserver-voice/../escape\n")
        URLClassLoader(arrayOf(root.toURI().toURL()), null).use { loader ->
            assertFailsWith<IllegalArgumentException> { extractBundledVoiceWorker(loader, worker) }
        }
    }

    @Test fun rejectsUnboundedAndMalformedPcmBeforeInference() {
        assertFailsWith<IllegalArgumentException> { Inference.pcmToFloat(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { Inference.pcmToFloat(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { Inference.pcmToFloat(ByteArray(960002)) }
        assertEquals(4, Inference.pcmToFloat(byteArrayOf(0, 0)).size)
    }
}
