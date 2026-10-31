package kinuvo.harness.browser

import kinuvo.harness.core.*
import kinuvo.harness.core.tools.*
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class BrowserPluginContractTest {
    private val owner = BrowserOwner("agent")
    private val agent = Agent("agent", "Agent", AgentConfig(tools = listOf("browser")))
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
        assertTrue(plugin.prepare(RunContext(agent.copy(config = agent.config.copy(tools = emptyList())), "session"), false).tools.isEmpty())
        val tools = plugin.prepare(RunContext(agent, "session"), false).ownedBy(plugin.instance).tools
        val capture = tools.single { it.identity?.localId == "browser_capture" }
        val result = capture.execute(buildJsonObject { put("tab", "tab") })
        assertEquals(owner, browser.commands.single().owner)
        assertFalse(result.contains("private-screenshot"))
        assertFalse(result.contains("private-thumbnail"))
        assertTrue(result.contains("Page text"))
        val second = plugin.prepare(RunContext(agent, "another-session"), false).ownedBy(plugin.instance).tools
        second.single { it.identity?.localId == "browser_capture" }.execute(buildJsonObject { put("tab", "tab") })
        assertEquals(listOf(owner, owner), browser.commands.map { it.owner })
        val firstGate = browser.gate(browser.commands.first().owner, "tab")
        val secondGate = browser.gate(browser.commands.last().owner, "tab")
        assertSame(firstGate, secondGate)
        plugin.close()
        assertTrue(browser.closed)
    }
    @Test fun anApprovalCannotSurviveTakeoverAndHandback() = runTest {
        val browser = Browser()
        val plugin = BrowserPlugin(browser)
        val click = plugin.prepare(RunContext(agent, "session"), false).ownedBy(plugin.instance).tools.single { it.identity?.localId == "browser_click" }
        val bound = click.bind(buildJsonObject { put("tab", "tab"); put("ref", "e1"); put("revision", "revision") })
        val held = browser.gate.acquire("device", 0)
        browser.gate.release("device", held.generation)
        assertFailsWith<IllegalStateException> { bound() }
        assertTrue(browser.commands.isEmpty())
    }
}
