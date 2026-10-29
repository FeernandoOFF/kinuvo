package kinuvo.voice

import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class InferenceTest {
    @Test fun cancellationKillsAndReapsWorkerBeforeReturning() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Mac")) return@runBlocking
        val root = Files.createTempDirectory("voice-cancel").toFile()
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
    @Test fun rejectsUnboundedAndMalformedPcmBeforeInference() {
        assertFailsWith<IllegalArgumentException> { Inference.pcmToFloat(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { Inference.pcmToFloat(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { Inference.pcmToFloat(ByteArray(960002)) }
        assertEquals(4, Inference.pcmToFloat(byteArrayOf(0, 0)).size)
    }
}
