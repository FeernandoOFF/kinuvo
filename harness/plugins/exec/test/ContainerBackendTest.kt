package kinuvo.harness.exec

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ContainerBackendTest {
    @Test
    fun mountPointsAreUnique() {
        assertEquals(
            listOf("/workspace/app", "/workspace/app-2", "/workspace/lib"),
            ContainerBackend.mountPoints(listOf(File("/a/app"), File("/b/app"), File("/c/lib"))),
        )
    }

    /** Needs a running podman/docker; KINUVO_CONTAINER=1 to enable. */
    @Test
    fun runsInIsolatedContainerWithWorkspaceMounted() {
        if (System.getenv("KINUVO_CONTAINER") != "1") return
        val ws = Files.createTempDirectory("harness-cws").toFile().canonicalFile
        File(ws, "host.txt").writeText("from host")
        val id = "test-${System.nanoTime()}"
        val backend = ContainerBackend(id, listOf(ws), "docker.io/library/debian:stable-slim", network = false, env = mapOf("GREETING" to "hola"))
        runBlocking {
            try {
                val root = backend.roots.single()
                assertEquals("from host", backend.read("host.txt"))
                backend.write("made/in.txt", "from container")
                assertEquals("from container", File(ws, "made/in.txt").readText())
                assertEquals("$root hola", backend.run("echo \"$(pwd) \$GREETING\"", null, 30.seconds).output)
                val net = backend.run("getent hosts example.com || echo no-network", null, 30.seconds).output
                assertTrue("no-network" in net, net)
            } finally {
                runProcess(listOf(ContainerBackend.detectRuntime(), "rm", "-f", "kinuvo-$id"), timeout = 60.seconds)
            }
        }
    }
}
