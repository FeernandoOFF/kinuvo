package kinuvo.plugins.browser

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.BrowserService
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.ScreenGate
import kinuvo.server.plugin.SecretResolver
import kinuvo.server.plugin.instance
import kinuvo.server.plugin.ownedBy
import kinuvo.protocol.approval.*
import kinuvo.protocol.browser.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class BrowserPluginContractTest {
    private val owner = BrowserOwner(AgentId("agent"))
    private val agent = Agent(AgentId("agent"), "Agent", AgentConfig(tools = listOf("browser")))
    private class Browser : BrowserService {
        val gate = ScreenGate()
        val commands = mutableListOf<BrowserCommand>()
        var closed = false
        override fun openingGate(owner: BrowserOwner) = gate
        override suspend fun gate(owner: BrowserOwner, tabId: String) = gate
        override suspend fun tabs(owner: BrowserOwner) = emptyList<BrowserTab>()
        override suspend fun command(command: BrowserCommand, user: Boolean): BrowserFrame {
            commands += command
            return BrowserFrame(BrowserTab("tab", command.owner.agentId, "Title", "https://example.test", thumbnail = "private-thumbnail"),
                "revision", screenshot = "private-screenshot", text = "Page text")
        }
        override suspend fun close() { closed = true }
    }
    @Test fun toolsShareAgentOwnershipAcrossSessionsAndDoNotPutImagesInTextResults() = runTest {
        val browser = Browser()
        val plugin = BrowserPlugin(browser)
        assertTrue(plugin.prepare(RunContext(agent, null), false).tools.isEmpty())
        assertTrue(plugin.prepare(RunContext(agent.copy(config = agent.config.copy(tools = emptyList())), SessionId("session")), false).tools.isEmpty())
        val tools = plugin.prepare(RunContext(agent, SessionId("session")), false).ownedBy(plugin.instance).tools
        val capture = tools.single { it.identity?.localId == "browser_capture" }
        val result = capture.execute(buildJsonObject { put("tab", "tab") })
        assertEquals(owner, browser.commands.single().owner)
        assertFalse(result.contains("private-screenshot"))
        assertFalse(result.contains("private-thumbnail"))
        assertTrue(result.contains("Page text"))
        val second = plugin.prepare(RunContext(agent, SessionId("another-session")), false).ownedBy(plugin.instance).tools
        second.single { it.identity?.localId == "browser_capture" }.execute(buildJsonObject { put("tab", "tab") })
        assertEquals(listOf(owner, owner), browser.commands.map { it.owner })
        val firstGate = browser.gate(browser.commands.first().owner, "tab")
        val secondGate = browser.gate(browser.commands.last().owner, "tab")
        assertSame(firstGate, secondGate)
        plugin.close()
        assertTrue(browser.closed)
    }
    @Test fun historyToolsOnlyNeedTheAgentOwnedTab() = runTest {
        val browser = Browser()
        val plugin = BrowserPlugin(browser)
        val tools = plugin.prepare(RunContext(agent, SessionId("session")), false).ownedBy(plugin.instance).tools
        for (name in listOf("browser_back", "browser_forward")) {
            val tool = tools.single { it.identity?.localId == name }
            assertEquals(Risk.Low, tool.risk)
            tool.execute(buildJsonObject { put("tab", "tab") })
        }
        assertEquals(listOf(BrowserAction.Back, BrowserAction.Forward), browser.commands.map { it.action })
        assertTrue(browser.commands.all { it.owner == owner && it.tabId == "tab" && it.text == null })
        plugin.close()
    }

    @Test fun anApprovalCannotSurviveTakeoverAndHandback() = runTest {
        val browser = Browser()
        val plugin = BrowserPlugin(browser)
        val click = plugin.prepare(RunContext(agent, SessionId("session")), false).ownedBy(plugin.instance).tools.single { it.identity?.localId == "browser_click" }
        val bound = click.bind(buildJsonObject { put("tab", "tab"); put("ref", "e1"); put("revision", "revision") })
        val held = browser.gate.acquire("device", 0)
        browser.gate.release("device", held.generation)
        assertFailsWith<IllegalStateException> { bound() }
        assertTrue(browser.commands.isEmpty())
    }

    @Test fun typingResolvesASecretReferenceForThisSessionOnly() = runTest {
        val browser = Browser()
        val scopes = mutableListOf<SessionId>()
        val secrets = object : SecretResolver {
            override fun scoped(agentId: AgentId, sessionId: SessionId): SecretResolver { scopes += sessionId; return this }
            override suspend fun resolve(uri: String, reason: String) = if (uri == "secret://id") "hunter22" else error("Can't read $uri")
        }
        val tools = BrowserPlugin(browser, secrets).prepare(RunContext(agent, SessionId("session")), false).ownedBy(BrowserPlugin(browser).instance).tools
        fun tool(name: String) = tools.single { it.identity?.localId == name }
        tool("browser_type").execute(buildJsonObject { put("tab", "tab"); put("revision", "revision"); put("ref", "e1"); put("text", "\${secret://id}") })
        tool("browser_type").execute(buildJsonObject { put("tab", "tab"); put("revision", "revision"); put("ref", "e1"); put("text", "hi \${secret://id}") })
        tool("browser_navigate").execute(buildJsonObject { put("tab", "tab"); put("text", "\${secret://id}") })
        assertEquals(listOf("hunter22", "hi \${secret://id}", "\${secret://id}"), browser.commands.map { it.text })
        assertEquals(listOf(SessionId("session")), scopes)
    }
}
