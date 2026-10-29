package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import kinuvo.client.KinuvoClient
import kinuvo.harness.cua.DesktopComputer
import kinuvo.harness.cua.Driver
import kinuvo.harness.server.Harness
import kinuvo.harness.server.HarnessServer
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*

class ComputerTest {
    @Test fun cliDesktopSupportsLiveViewInputAndTakeoverThroughScreens() = runBlocking {
        withTimeout(10_000) {
            val home = Files.createTempDirectory("kinuvo-cli-computer").toFile()
            val server = HarnessServer(Harness(Path(home.path), plugins = emptyList()))
            val calls = mutableListOf<Pair<String, JsonObject>>()
            val png = java.io.ByteArrayOutputStream().also {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()
            var closed = false
            var awake = false
            val driver = object : Driver {
                override fun keepAwake(active: Boolean) { awake = active }
                override suspend fun call(tool: String, args: JsonObject): JsonObject {
                    calls += tool to args
                    return if (tool == "get_desktop_state") buildJsonObject {
                        put("capture_id", "desktop-1"); put("screen_width", 1200); put("screen_height", 800)
                        put("screenshot_png_b64", Base64.encode(png))
                    } else JsonObject(emptyMap())
                }
                override fun close() { closed = true }
            }
            val port = server.start("0.0.0.0", 0, schedule = false)
            val desktop = CliComputer(server, "0.0.0.0", port, DesktopComputer { driver })
            val bootstrap = KinuvoClient("ws://127.0.0.1:$port/ws", "Bootstrap", server.auth.sessionToken())
            val code = try { bootstrap.connect(); bootstrap.call(Api.Pairing, PairingParams(refresh = true, management = true)).code } finally { bootstrap.close() }
            val observer = KinuvoClient("ws://127.0.0.1:$port/ws", "Observer", pairingCode = code)
            try {
                observer.connect()
                assertEquals(emptyList(), observer.call(Api.Computers, Unit))
                desktop.connect()
                val computer = observer.call(Api.Computers, Unit).single()
                assertEquals("Server desktop", computer.name)
                assertTrue(computer.available)
                val ref = ScreenRef("computer", computer.id)
                val frames = async(start = CoroutineStart.UNDISPATCHED) { observer.events(Api.ScreenFrame).first() }
                val watch = observer.call(Api.ScreenWatch, ScreenWatch(ref))
                val frame = frames.await()
                assertEquals(watch.id, frame.subscription)
                assertFalse(awake)
                observer.call(Api.ScreenAck, ScreenFrameAck(frame.subscription, frame.epoch, frame.sequence))
                val generation = observer.call(Api.Screens, null).single { it.ref == ref }.control.generation
                val held = observer.call(Api.ScreenControl, ScreenControlRequest(ref, generation, true))
                assertTrue(awake)
                assertEquals(ComputerControl.User, observer.call(Api.Computers, Unit).single().control)
                val input = observer.call(Api.ScreenInput, ScreenInputRequest(watch.id, ScreenInput(ScreenFrameAck(frame.subscription, frame.epoch, frame.sequence),
                    frame.sample.geometry, held.generation, 0, listOf(ScreenAction(ScreenActionKind.Key, key = "ctrl+a")))))
                assertEquals(1, input.applied, input.message)
                assertEquals(listOf("ctrl", "a"), calls.first { it.first == "hotkey" }.second["keys"]?.jsonArray?.map { it.jsonPrimitive.content })
                observer.call(Api.ScreenControl, ScreenControlRequest(ref, held.generation, false))
                assertFalse(awake)
                assertEquals(ComputerControl.Idle, observer.call(Api.Computers, Unit).single().control)
                observer.call(Api.ScreenUnwatch, watch.id)
                desktop.close()
                while (observer.call(Api.Computers, Unit).single().available) delay(10)
                assertTrue(closed)
            } finally {
                server.stop(); desktop.close(); observer.close(); home.deleteRecursively()
            }
        }
    }

    @Test fun missingDriverDoesNotRegisterAComputer() {
        val home = Files.createTempDirectory("kinuvo-cli-no-computer").toFile()
        val server = HarnessServer(Harness(Path(home.path), plugins = emptyList()))
        try {
            val error = assertFailsWith<CliktError> { CliComputer(server, "127.0.0.1", 0, DesktopComputer(connect = null)) }
            assertContains(error.message.orEmpty(), "cua-driver permissions grant")
            assertTrue(server.auth.toolDevices().isEmpty())
        } finally { server.stop(); home.deleteRecursively() }
    }
}
