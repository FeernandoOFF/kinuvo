package kinuvo.harness.server

import kinuvo.client.KinuvoClient
import kinuvo.harness.browser.BrowserPlugin
import kinuvo.harness.testing.scriptedModel
import kinuvo.harness.testing.toolResults
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** With KINUVO_BROWSER_PACKAGED_JAR set, the same proof runs against the executable jar. */
class BrowserServerTest {
    @Test fun agentRunCanOpenBrowserThroughTheRealToolLoop(): Unit = runBlocking {
        val root = File(System.getenv("KINUVO_HOME") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }
        val home = Files.createTempDirectory(root.toPath(), "browser-agent-").toFile()
        val harness = Harness(Path(home.path), plugins = listOf(BrowserPlugin(File(home, "browser"))),
            modelFactory = { scriptedModel { prompt, tools ->
                if (prompt.messages.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("open", tools.first { "browser_open" in it.name }.name, """{"text":"about:blank"}"""), StreamFrame.End())
                else flowOf(StreamFrame.TextDelta("Browser opened"), StreamFrame.End())
            } })
        try {
            val base = harness.store.agents().first()
            harness.store.saveConfig(base, base.config.copy(tools = listOf("browser"), approvals = "full"))
            val agent = harness.store.agents().first()
            val session = harness.newSession(agent)
            harness.runner(agent, session.id).run(session.id, "Open a browser").toList()
            val entries = harness.store.entries(agent.id, session.id)
            val result = entries.filterIsInstance<SessionEntry.ToolResult>().single()
            assertFalse(result.isError, result.toString())
            assertEquals(1, harness.browser!!.tabs(BrowserOwner(agent.id, session.id)).size)
        } finally { harness.close() }
    }

    @Test fun pairedDeviceCanBrowseAndTakeOverButOrdinaryDeviceCannot(): Unit = runBlocking {
        val root = File(System.getenv("KINUVO_HOME") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }
        val home = System.getenv("KINUVO_BROWSER_PROOF_HOME")?.let { File(it).apply { mkdirs() } }
            ?: Files.createTempDirectory(root.toPath(), "browser-server-").toFile()
        val jar = System.getenv("KINUVO_BROWSER_PACKAGED_JAR")
        var server: HarnessServer? = null
        var process: Process? = null
        var client: KinuvoClient? = null
        var ordinary: KinuvoClient? = null
        try {
            val local = if (jar != null) {
                process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath, "-jar", jar, "serve", "--host", "127.0.0.1", "--port", "0")
                    .apply { environment()["KINUVO_HOME"] = home.absolutePath; environment().remove("KINUVO_EMBEDDED_PORT") }
                    .redirectErrorStream(true).redirectOutput(File(home, "process.log")).start()
                withTimeout(30_000) {
                    var found: LocalServer? = null
                    while (found == null) { check(process!!.isAlive) { "Packaged Server exited: ${File(home, "process.log").readText()}" }; found = LocalServer.read(Path(home.path)); delay(100) }
                    found
                }
            } else {
                val harness = Harness(Path(home.path), modelFactory = { scriptedModel { _, _ -> emptyFlow() } },
                    plugins = listOf(BrowserPlugin(File(home, "browser"))))
                server = HarnessServer(harness)
                val port = server.start("127.0.0.1", 0, schedule = false)
                LocalServer("127.0.0.1", port, server.auth.sessionToken(), ProcessHandle.current().pid())
            }
            val address = "ws://127.0.0.1:${local.port}/ws"
            var activeAddress = address
            val bootstrap = KinuvoClient(address, "Local pairing bootstrap", token = local.token)
            val pairingCode = try {
                bootstrap.connect()
                bootstrap.call(Api.Pairing, PairingParams(refresh = true, management = true)).code
            } finally { bootstrap.close() }
            client = KinuvoClient(address, "Paired Browser proof", pairingCode = pairingCode)
            val pairedToken = client.connect().token
            assertTrue(ServerFeature.Browser in client.lastConnect!!.capabilities.features)
            val agent = client.call(Api.Agents, Unit).first()
            val session = client.call(Api.CreateSession, AgentRef(agent.id))
            val owner = BrowserOwner(agent.id, session.id)
            val opened = client.call(Api.ScreenOpen, ScreenOpen(owner))
            val ref = opened.ref
            val initial = client.call(Api.Screens, owner).single { it.ref == ref }
            assertNotNull(initial.control.holder)
            assertFailsWith<IllegalStateException> { client.call(Api.Screens, owner.copy(sessionId = "missing")) }
            assertFailsWith<IllegalStateException> { client.call(Api.BrowserCommand, BrowserCommand(owner, BrowserAction.Takeover, ref.id, take = false)) }
            val pairing = client.call(Api.Pairing, PairingParams(refresh = true, management = false))
            ordinary = KinuvoClient(address, "Ordinary", pairingCode = pairing.code)
            ordinary.connect()
            assertContains(assertFailsWith<IllegalStateException> { ordinary.call(Api.Screens, owner) }.message.orEmpty(), "management")
            assertFailsWith<IllegalStateException> { ordinary.call(Api.ScreenWatch, ScreenWatch(ref)) }
            assertFailsWith<IllegalStateException> { ordinary.call(Api.ScreenControl, ScreenControlRequest(ref, initial.control.generation, false)) }
            val images = kotlinx.coroutines.channels.Channel<ScreenFrame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val viewingClient = client
            val watching = launch(start = CoroutineStart.UNDISPATCHED) { viewingClient.events(Api.ScreenFrame).collect { images.send(it) } }
            val subscription = client.call(Api.ScreenWatch, ScreenWatch(ref))
            val image = withTimeout(15_000) { images.receive() }
            assertEquals(subscription.epoch, image.epoch)
            assertTrue(image.sample.image.length > 1000)
            val ack = ScreenFrameAck(image.subscription, image.epoch, image.sequence)
            assertFailsWith<IllegalStateException> { ordinary.call(Api.ScreenAck, ack) }
            client.call(Api.ScreenAck, ack)
            val input = ScreenInputRequest(subscription.id, ScreenInput(ack, image.sample.geometry, initial.control.generation, 0,
                listOf(ScreenAction(ScreenActionKind.Key, key = "Tab"))))
            assertEquals(1, client.call(Api.ScreenInput, input).applied)
            assertFailsWith<IllegalStateException> { client.call(Api.ScreenInput, input) }
            // Responses use the durable lane even while a viewer stops drawing.
            repeat(20) { assertTrue(client.call(Api.Agents, Unit).isNotEmpty()) }
            watching.cancelAndJoin()
            client.close()
            client = KinuvoClient(address, "Browser proof reconnect", token = pairedToken)
            client.connect()
            val suspended = withTimeout(5000) {
                var control = client.call(Api.Screens, owner).single { it.ref == ref }.control
                while (!control.suspended) { delay(20); control = client.call(Api.Screens, owner).single { it.ref == ref }.control }
                control
            }
            assertFailsWith<IllegalStateException> { client.call(Api.ScreenAck, ack) }
            assertFailsWith<IllegalStateException> { client.call(Api.ScreenCommand, ScreenCommand(ref, initial.control.generation, ScreenCommandKind.Navigate, "about:blank")) }
            if (jar == null) {
                client.close()
                server!!.stop()
                val restartedHarness = Harness(Path(home.path), modelFactory = { scriptedModel { _, _ -> emptyFlow() } }, plugins = listOf(BrowserPlugin(File(home, "browser"))))
                server = HarnessServer(restartedHarness)
                val restartedPort = server.start("127.0.0.1", 0, schedule = false)
                activeAddress = "ws://127.0.0.1:$restartedPort/ws"
                client = KinuvoClient(activeAddress, "Restored viewer", token = pairedToken)
                client.connect()
                val restored = client.call(Api.Screens, owner).single { it.ref == ref }
                assertTrue(restored.control.suspended)
                assertEquals(initial.control.holder, restored.control.holder)
            }
            val current = client.call(Api.Screens, owner).single { it.ref == ref }.control
            val held = client.call(Api.ScreenControl, ScreenControlRequest(ref, current.generation, true))
            assertFailsWith<IllegalStateException> { client.call(Api.ScreenControl, ScreenControlRequest(ref, suspended.generation, false)) }
            val secondPairing = client.call(Api.Pairing, PairingParams(refresh = true, management = true))
            val second = KinuvoClient(activeAddress, "Second management viewer", pairingCode = secondPairing.code)
            try {
                second.connect()
                second.call(Api.ScreenWatch, ScreenWatch(ref))
                assertFailsWith<IllegalStateException> { second.call(Api.ScreenControl, ScreenControlRequest(ref, held.generation, true)) }
                val stolen = second.call(Api.ScreenControl, ScreenControlRequest(ref, held.generation, true, steal = true))
                assertNotEquals(held.holder, stolen.holder)
                assertFailsWith<IllegalStateException> { client.call(Api.ScreenCommand, ScreenCommand(ref, held.generation, ScreenCommandKind.Close)) }
                client.call(Api.RevokeDevice, DeviceRef(requireNotNull(stolen.holder)))
                val revoked = withTimeout(5000) {
                    var value = client.call(Api.Screens, owner).single { it.ref == ref }.control
                    while (!value.suspended) { delay(20); value = client.call(Api.Screens, owner).single { it.ref == ref }.control }
                    value
                }
                val finalHold = client.call(Api.ScreenControl, ScreenControlRequest(ref, revoked.generation, true))
                client.call(Api.ScreenCommand, ScreenCommand(ref, finalHold.generation, ScreenCommandKind.Close))
            } finally { second.close() }
            assertTrue(client.call(Api.BrowserTabs, owner).isEmpty())
            println("Browser Server proof: ${if (jar == null) "JVM classpath" else "packaged executable jar"}; home ${home.absolutePath}")
        } finally {
            ordinary?.close(); client?.close(); server?.stop()
            process?.let { it.destroy(); if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly() }
        }
    }
}
