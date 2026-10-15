package kinuvo.harness.server

import kinuvo.harness.core.HarnessPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ServerLifecycleTest {
    @Test
    fun failedHostStartClosesInstallationAndRepeatedStopDoesNotCloseAgain() = runBlocking {
        val home = Files.createTempDirectory("failed-server-start").toFile()
        var closes = 0
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.serverlifecycletest.1", "Test")
            override suspend fun close() { closes++ }
        }
        val server = HarnessServer(Harness(Path(home.path), plugins = listOf(plugin)), network = NetworkSecurity("https://kinuvo.example.com"))
        try {
            assertFailsWith<IllegalArgumentException> { server.start("0.0.0.0", 8787, schedule = false) }
            assertEquals(1, closes)
            server.stop()
            server.stop()
            assertEquals(1, closes)
        } finally { server.stop(); home.deleteRecursively() }
    }

    @Test
    fun failedListenerStartClosesInstallation() = runBlocking {
        val home = Files.createTempDirectory("failed-listener-start").toFile()
        var closes = 0
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.serverlifecycletest.2", "Test")
            override suspend fun close() { closes++ }
        }
        val server = HarnessServer(Harness(Path(home.path), plugins = listOf(plugin)), network = NetworkSecurity("https://kinuvo.example.com"))
        try {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
                assertFailsWith<Exception> { server.start("127.0.0.1", socket.localPort, schedule = false) }
            }
            assertEquals(1, closes)
        } finally { server.stop(); home.deleteRecursively() }
    }

}
