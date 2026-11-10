package kinuvo.harness.server

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kinuvo.harness.testing.awaitValue
import kinuvo.harness.server.screens.*
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
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
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
            harness.store.saveConfig(base, base.config.copy(tools = listOf("browser"), approvals = "default"))
            val agent = harness.store.agents().first()
            val session = harness.newSession(agent)
            harness.runner(agent, session.id).run(session.id, "Open a browser").toList()
            val entries = harness.store.entries(agent.id, session.id)
            val result = entries.filterIsInstance<SessionEntry.ToolResult>().single()
            assertFalse(result.isError, result.toString())
            val tabs = harness.browser!!.tabs(BrowserOwner(agent.id))
            val secondSession = harness.newSession(agent)
            harness.runner(agent, secondSession.id).run(secondSession.id, "Open another browser tab").toList()
            assertEquals(2, harness.browser!!.tabs(BrowserOwner(agent.id)).size)
            harness.log.deleteSession(agent.id, session.id)
            assertEquals(2, harness.browser!!.tabs(BrowserOwner(agent.id)).size)
            assertEquals(tabs.single().id, harness.browser!!.tabs(BrowserOwner(agent.id)).first().id)
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
        val fixture = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fixture.createContext("/") { exchange ->
            val html = "<title>${exchange.requestURI.path}</title>History fixture".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        fixture.start()
        try {
            val local = if (jar != null) {
                process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath, "-jar", jar, "serve", "--host", "127.0.0.1", "--port", "0")
                    .apply { environment()["KINUVO_HOME"] = home.absolutePath; environment().remove("KINUVO_EMBEDDED_PORT") }
                    .redirectErrorStream(true).redirectOutput(File(home, "process.log")).start()
                awaitValue("the packaged Server", 30.seconds, 100.milliseconds) {
                    check(process!!.isAlive) { "Packaged Server exited: ${File(home, "process.log").readText()}" }
                    LocalServer.read(Path(home.path))
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
            val owner = BrowserOwner(agent.id)
            val opened = client.call(Api.ScreenOpen, ScreenOpen(owner))
            val ref = opened.ref
            client.call(Api.CreateSession, AgentRef(agent.id))
            assertEquals(listOf(ref.id), client.call(Api.BrowserTabs, BrowserOwner(agent.id)).map { it.id })
            val initial = client.call(Api.Screens, owner).single { it.ref == ref }
            assertNotNull(initial.control.holder)
            assertTrue(initial.capabilities.resize)
            client.call(Api.ScreenCommand, ScreenCommand(ref, initial.control.generation, ScreenCommandKind.Resize, width = 390, height = 600))
            val fixtureUrl = "http://127.0.0.1:${fixture.address.port}"
            for (path in listOf("first", "second")) client.call(Api.ScreenCommand,
                ScreenCommand(ref, initial.control.generation, ScreenCommandKind.Navigate, "$fixtureUrl/$path"))
            client.call(Api.ScreenCommand, ScreenCommand(ref, initial.control.generation, ScreenCommandKind.Back))
            assertEquals("/first", client.call(Api.Screens, owner).single { it.ref == ref }.title)
            client.call(Api.ScreenCommand, ScreenCommand(ref, initial.control.generation, ScreenCommandKind.Forward))
            assertEquals("/second", client.call(Api.Screens, owner).single { it.ref == ref }.title)
            assertFailsWith<IllegalStateException> { client.call(Api.Screens, owner.copy(agentId = "missing")) }
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
            assertEquals(390, image.sample.width)
            assertEquals(600, image.sample.height)
            val ack = ScreenFrameAck(image.subscription, image.epoch, image.sequence)
            assertFailsWith<IllegalStateException> { ordinary.call(Api.ScreenAck, ack) }
            for (action in listOf(ScreenCommandKind.Back, ScreenCommandKind.Forward, ScreenCommandKind.Resize))
                assertFailsWith<IllegalStateException> { ordinary.call(Api.ScreenCommand, ScreenCommand(ref, initial.control.generation, action)) }
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
            val reconnected = client
            val suspended = awaitValue("the Screen to suspend", 5.seconds) { reconnected.call(Api.Screens, owner).single { it.ref == ref }.control.takeIf { it.suspended } }
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
                val revoked = awaitValue("the Screen to suspend", 5.seconds) { client.call(Api.Screens, owner).single { it.ref == ref }.control.takeIf { it.suspended } }
                val finalHold = client.call(Api.ScreenControl, ScreenControlRequest(ref, revoked.generation, true))
                client.call(Api.ScreenCommand, ScreenCommand(ref, finalHold.generation, ScreenCommandKind.Close))
            } finally { second.close() }
            assertTrue(client.call(Api.BrowserTabs, owner).isEmpty())
            println("Browser Server proof: ${if (jar == null) "JVM classpath" else "packaged executable jar"}; home ${home.absolutePath}")
        } finally {
            ordinary?.close(); client?.close(); server?.stop(); fixture.stop(0)
            process?.let { it.destroy(); if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly() }
        }
    }
}
