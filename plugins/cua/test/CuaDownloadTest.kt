package kinuvo.plugins.cua

import java.io.File
import java.nio.file.Files
import kinuvo.protocol.computer.ComputerRequest
import kinuvo.protocol.device.*
import kotlinx.coroutines.*
import kotlin.test.*

class CuaDownloadTest {
    @Test fun assets() {
        val home = File("unused")
        for (platform in listOf(Platform.MacOS, Platform.Windows, Platform.Linux)) for (arch in listOf(Arch.X64, Arch.Arm64)) {
            val engine = assertNotNull(cuaDriverEngine(home, platform, arch))
            val target = when (platform) { Platform.MacOS -> "darwin-universal"; Platform.Windows -> "windows"; else -> "linux" }
            assertTrue(engine.download.files.single().url.contains(target))
            if (platform != Platform.MacOS) assertTrue(engine.download.id.endsWith(if (arch == Arch.X64) "x86_64" else "arm64"))
        }
        assertNull(cuaDriverEngine(home, Platform.Android, Arch.Arm64))
        assertNull(cuaDriverEngine(home, Platform.Windows, Arch.Unknown))
    }

    @Test fun discoveryWins() = runBlocking {
        val source = CuaDriverDownload(File("unused"), discover = { "installed" }, download = { error("must not download") })
        try { assertEquals("installed", source.resolve()) } finally { source.close() }
    }

    @Test fun downloadingAndFailure() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val source = CuaDriverDownload(File("unused"), discover = { null }, download = { calls++; gate.await(); error("offline") })
        val computer = DesktopComputer(source, null)
        try {
            assertTrue(computer.ready)
            repeat(2) { assertEquals("Downloading cua-driver for Computer use. Try again in a minute.", computer.perform(ComputerRequest()).message) }
            gate.complete(Unit)
            val failed = withTimeout(5000) {
                var message: String?
                do { message = computer.perform(ComputerRequest()).message; delay(10) } while (message == "Downloading cua-driver for Computer use. Try again in a minute.")
                message
            }
            assertEquals("cua-driver download failed: offline", failed)
            assertEquals(1, calls)
            assertEquals("Downloading cua-driver for Computer use. Try again in a minute.", computer.perform(ComputerRequest()).message)
            withTimeout(5000) { while (calls < 2) delay(10) }
        } finally { source.close(); computer.close() }
    }

    @Test fun downloadedMacDriver() = runBlocking {
        if (currentPlatform != Platform.MacOS || System.getenv("KINUVO_CUA_DOWNLOAD_TEST") != "1") return@runBlocking
        val home = Files.createTempDirectory("kinuvo-cua-download-").toFile()
        val engine = assertNotNull(cuaDriverEngine(home))
        val source = CuaDriverDownload(home, discover = { null }, engine = engine, grant = {})
        try {
            assertNull(source.resolve())
            val path = withTimeout(300_000) {
                var path: String? = null
                while (path == null) { check(!source.message.contains("failed")) { source.message }; delay(100); path = source.resolve() }
                path
            }
            val driver = McpDriver(path, listOf("--direct"))
            try { println("Downloaded driver tools: ${driver.tools().sorted().joinToString()}") } finally { driver.close() }
            val app = File(engine.folder, "${engine.download.id}/CuaDriver.app")
            for (command in listOf(listOf(path, "permissions", "status", "--json"), listOf("codesign", "--verify", "--deep", "--strict", app.path), listOf("spctl", "-a", "-vv", app.path))) {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                println(command.joinToString(" ") + "\n" + process.inputStream.bufferedReader().readText())
                assertEquals(0, process.waitFor())
            }
            println("Downloaded bundle: ${app.path}")
        } finally { source.close() }
    }
}
