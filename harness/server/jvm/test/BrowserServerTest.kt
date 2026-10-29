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
        val harness = Harness(Path(home.path), plugins = listOf(BrowserPlugin(File(root, "browser-runtime-proof"))),
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
            val entries = harness.store.entries(agent, session.id)
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
                    plugins = listOf(BrowserPlugin(File(root, "browser-runtime-proof"))))
                server = HarnessServer(harness)
                val port = server.start("127.0.0.1", 0, schedule = false)
                LocalServer("127.0.0.1", port, server.auth.sessionToken(), ProcessHandle.current().pid())
            }
            val address = "ws://127.0.0.1:${local.port}/ws"
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
            val frame = assertNotNull(client.call(Api.BrowserCommand, BrowserCommand(owner, BrowserAction.Open, text = "about:blank")))
            assertTrue(frame.tab.takeover)
            assertTrue(frame.screenshot!!.length > 1000)
            assertEquals(frame.tab.id, client.call(Api.BrowserTabs, owner).single().id)
            assertFailsWith<IllegalStateException> { client.call(Api.BrowserTabs, owner.copy(sessionId = "missing")) }
            val pairing = client.call(Api.Pairing, PairingParams(refresh = true, management = false))
            ordinary = KinuvoClient(address, "Ordinary", pairingCode = pairing.code)
            ordinary.connect()
            val denied = assertFailsWith<IllegalStateException> { ordinary.call(Api.BrowserTabs, owner) }
            assertContains(denied.message.orEmpty(), "management")
            client.call(Api.BrowserCommand, BrowserCommand(owner, BrowserAction.Takeover, frame.tab.id, take = false))
            assertFailsWith<IllegalStateException> { client.call(Api.BrowserCommand, BrowserCommand(owner, BrowserAction.Key, frame.tab.id, text = "enter")) }
            // No Device attached: browsing is still owned by the continuously running Server.
            client.close()
            client = KinuvoClient(address, "Browser proof reconnect", token = pairedToken)
            client.connect()
            assertEquals(frame.tab.id, client.call(Api.BrowserTabs, owner).single().id)
            client.call(Api.BrowserCommand, BrowserCommand(owner, BrowserAction.Takeover, frame.tab.id, take = true))
            client.call(Api.BrowserCommand, BrowserCommand(owner, BrowserAction.Close, frame.tab.id))
            assertTrue(client.call(Api.BrowserTabs, owner).isEmpty())
            println("Browser Server proof: ${if (jar == null) "JVM classpath" else "packaged executable jar"}; home ${home.absolutePath}")
        } finally {
            ordinary?.close(); client?.close(); server?.stop()
            process?.let { it.destroy(); if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly() }
        }
    }
}
