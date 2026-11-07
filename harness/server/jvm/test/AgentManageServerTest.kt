package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.plugin.ModelProvider
import kinuvo.harness.core.plugin.PluginTools
import kinuvo.harness.core.plugin.RunContext
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentManageServerTest {
    private val dir: File = Files.createTempDirectory("kinuvo-manage").toFile()
    private val advertised = mutableMapOf<String, List<String>>()
    private var script: Pair<String, JsonObject>? = null

    private val mcpLike = object : HarnessPlugin {
        override val definition = PluginDefinition("kinuvo.mcp", "MCP")
        override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
            override val descriptor = ToolDescriptor("mcp_fake_search", "Searches an outside service")
            override suspend fun execute(args: JsonObject) = "found"
        }))
    }

    private fun provider(name: String, local: Boolean) = object : HarnessPlugin, ModelProvider {
        override val definition = PluginDefinition("test.$name", "Test")
        override val id = name
        override val modelProviders get() = listOf(this)
        override fun onDevice(model: String) = local
        override fun model(config: AgentConfig): AgentModel = scriptedModel { prompt, tools ->
            advertised["${config.provider}/${config.model}"] = tools.map { it.name }
            val call = if (config.model == "manager") script?.also { script = null } else null
            when {
                config.model == "manager" && call != null -> flowOf(StreamFrame.ToolCallComplete("c1", call.first, call.second.toString()), StreamFrame.End())
                config.model == "manager" -> flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
                else -> flowOf(StreamFrame.TextDelta("${config.model}: ok"), StreamFrame.End())
            }
        }
    }

    private val harness = Harness(Path(dir.path), plugins = listOf(provider("cloud", false), provider("local", true), mcpLike))
    private val server = HarnessServer(harness)
    private val admin get() = Admin(harness, Runs(harness.log, CoroutineScope(Dispatchers.Default))) {}
    private val port get() = server.runtime.mcp.url!!.substringAfterLast(':').substringBefore('/')

    private suspend fun agent(id: String, provider: String, model: String, approvals: String? = null) {
        if (harness.store.agents().none { it.id == id }) harness.store.createAgent(id)
        admin.update(UpdateAgentParams(id, provider = provider, model = model, approvals = approvals))
    }

    private fun obj(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is JsonElement -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }

    private suspend fun until(check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(20) }

    /** What a native run's [tool] call showed the user, and the tool result it got. */
    private class Outcome(val approval: AgentEvent.ApprovalRequested?, val output: String, val isError: Boolean)

    private suspend fun KinuvoClient.run(agentId: String, tool: String, args: JsonObject, sessionId: String? = null, allow: Boolean = true): Outcome {
        script = tool to args
        val id = sessionId ?: harness.newSession(harness.store.agents().first { it.id == agentId }).id
        var approval: AgentEvent.ApprovalRequested? = null
        suspend fun results() = harness.store.entries(agentId, id).filterIsInstance<SessionEntry.ToolResult>()
        val before = results().size
        call(Api.Send, SendParams(agentId, id, "go"))
        until {
            harness.log.live(id).approval?.let { approval = it; harness.approvals.respond(it.approvalId, if (allow) ApprovalDecision.AllowOnce else ApprovalDecision.Deny()) }
            results().size > before
        }
        val result = results().last()
        until { harness.log.status(id) == SessionStatus.Idle }
        return Outcome(approval, result.output, result.isError)
    }

    private fun test(block: suspend KinuvoClient.() -> Unit) = try {
        runBlocking {
            server.start("127.0.0.1", 0, schedule = false)
            agent("default", "cloud", "manager"); agent("private", "local", "small"); agent("ghost", "local", "manager")
            val client = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = server.auth.sessionToken())
            try { client.connect(); client.block() } finally { client.close() }
        }
    } finally { runBlocking { server.stop() }; dir.deleteRecursively() }

    private suspend fun hiddenSession(agentId: String, settings: SessionSettings? = null): String {
        val agent = harness.store.agents().first { it.id == agentId }
        val id = harness.newSession(agent).id
        harness.store.append(agentId, id, SessionEntry.Details(hidden = true))
        settings?.let { harness.configure(agent, id, it) }
        return id
    }

    private suspend fun config(id: String) = harness.store.agents().first { it.id == id }.config

    @Test fun anAgentUpdateShowsBeforeAndAfterInTheApprovalAndChangesNothingUntilAllowed() = test {
        val denied = run("default", "agent_update", obj("name" to "Dee", "soul" to "Short answers.", "reasoning" to "high"), allow = false)
        assertEquals(Risk.High, denied.approval!!.risk)
        assertFalse(denied.approval!!.trustable)
        val reason = denied.approval!!.reason!!
        assertTrue("Name: Kinuvo → Dee" in reason, reason)
        assertTrue("Reasoning: medium → high" in reason && "Soul:" in reason && "→ Short answers." in reason, reason)
        assertTrue(denied.isError)
        assertEquals("medium", config("default").reasoning)

        val allowed = run("default", "agent_update", obj("name" to "Dee", "soul" to "Short answers.", "reasoning" to "high"))
        assertFalse(allowed.isError, allowed.output)
        assertEquals("high", config("default").reasoning)
        assertEquals("Short answers.", harness.docs.read("default", AgentDoc.Soul).trim())
        assertEquals("Dee", harness.store.agents().first { it.id == "default" }.name)
    }

    @Test fun anAgentUpdateAsksEvenAtFullRiskTolerance() = test {
        agent("default", "cloud", "manager", approvals = "full")
        val outcome = run("default", "agent_update", obj("reasoning" to "low"))
        assertNotNull(outcome.approval)
    }

    @Test fun anAgentCannotLoosenItsOwnSafetyThroughAgentUpdate() = test {
        harness.trustRules.update("default") { it.copy(deny = DenyRules(tools = listOf("shell"))) }
        for (forbidden in listOf("approvals" to "full", "rules" to """{"deny":{"tools":[],"shell":[]}}""", "trusted" to "shell")) {
            val outcome = run("default", "agent_update", obj(forbidden))
            assertNull(outcome.approval, forbidden.first)
            assertTrue(outcome.isError && "in Settings" in outcome.output, outcome.output)
        }
        assertEquals("default", config("default").approvals)
        assertEquals(listOf("shell"), harness.trustRules.get("default").deny.tools)
        assertTrue(harness.trustRules.get("default").trusted.isEmpty())
    }

    @Test fun anAgentCannotEnableAToolGroupTheServerLacks() = test {
        assertFalse("browser" in harness.integrations[Integration.Tools].orEmpty())
        admin.update(UpdateAgentParams("default", tools = config("default").tools - "browser"))
        val outcome = run("default", "agent_update", obj("enable_tools" to "browser"))
        assertNull(outcome.approval)
        assertTrue(outcome.isError && "off on this Server" in outcome.output, outcome.output)
        assertFalse("browser" in config("default").tools)
    }

    @Test fun agentCreateMakesABlankAgentThatCopiesNothingFromTheCaller() = test {
        harness.docs.write("default", AgentDoc.Memory, "Ana likes tea")
        harness.trustRules.update("default") { it.copy(shell = listOf("git status")) }
        val outcome = run("default", "agent_create", obj("name" to "Scout", "soul" to "Curious."))
        assertTrue("Scout (scout), blank" in outcome.approval!!.reason!!, outcome.approval!!.reason)
        assertFalse(outcome.isError, outcome.output)
        val scout = harness.store.agents().first { it.id == "scout" }
        assertEquals("Scout", scout.name)
        assertEquals("", harness.docs.read("scout", AgentDoc.Memory).trim())
        assertTrue(harness.trustRules.get("scout").shell.isEmpty())
        assertEquals("Curious.", harness.docs.read("scout", AgentDoc.Soul).trim())
        assertEquals(listOf("git status"), harness.trustRules.get("default").shell)
    }

    @Test fun sessionConfigureChangesTheCurrentSessionOnly() = test {
        val outcome = run("default", "session_configure", obj("model" to "other", "reasoning" to "low"))
        assertEquals(Risk.Medium, outcome.approval!!.risk)
        assertTrue("Model: cloud/manager → cloud/other" in outcome.approval!!.reason!!, outcome.approval!!.reason)
        assertFalse(outcome.isError, outcome.output)
        assertEquals("manager", config("default").model)
        val session = harness.store.sessions("default").single()
        assertEquals(SessionSettings(model = "other", reasoning = "low"), session.settings)
    }

    @Test fun sessionConfigureCannotRaiseRiskToleranceEvenWhenTheUserCanThroughSettings() = test {
        val outcome = run("default", "session_configure", obj("approvals" to "full"))
        assertNull(outcome.approval)
        assertTrue(outcome.isError && "can't be raised" in outcome.output, outcome.output)
        val session = harness.store.sessions("default").single()
        assertNull(session.settings.approvals)
        harness.configure(harness.store.agents().first { it.id == "default" }, session.id, SessionSettings(approvals = "full"))
        assertEquals("full", harness.store.session("default", session.id).settings.approvals)
    }

    @Test fun aHiddenSessionKeepsAnOnDeviceModelWhoeverChangesIt() = test {
        val hidden = hiddenSession("ghost")
        val ghost = harness.store.agents().first { it.id == "ghost" }
        // The Agent's own tool.
        val tool = run("ghost", "session_configure", obj("provider" to "cloud", "model" to "manager"), sessionId = hidden)
        assertNull(tool.approval)
        assertTrue(tool.isError && "on-device" in tool.output, tool.output)
        // The user's Settings.
        assertFailsWith<IllegalArgumentException> { harness.configure(ghost, hidden, SessionSettings(provider = "cloud", model = "manager")) }
        val over = assertFails { call(Api.ConfigureSession, ConfigureSessionParams("ghost", hidden, SessionSettings(provider = "cloud", model = "x"))) }
        assertTrue("on-device" in over.message.orEmpty(), over.message)
        assertEquals(SessionSettings(), harness.store.session("ghost", hidden).settings)
        // Another on-device model is fine.
        val ok = run("ghost", "session_configure", obj("provider" to "local", "model" to "small2"), sessionId = hidden)
        assertFalse(ok.isError, ok.output)
        assertEquals("small2", harness.store.session("ghost", hidden).settings.model)
    }

    @Test fun aHiddenSessionOnlyGetsSessionConfigureFromTheManageGroup() = test {
        val hidden = hiddenSession("ghost")
        run("ghost", "session_configure", obj("reasoning" to "low"), sessionId = hidden)
        val names = advertised.getValue("local/manager")
        assertTrue("session_configure" in names)
        assertTrue(names.none { it in setOf("agent_update", "agent_create", "agent_get", "mcp_add", "mcp_remove", "mcp_list", "mcp_test", "mcp_login") }, names.toString())
    }

    @Test fun changingAnAgentsDefaultModelCannotMoveAHiddenSessionOffTheDevice() = test {
        // One that follows its Agent's default, and one delegated, which keeps the model it started with.
        val following = hiddenSession("private")
        admin.update(UpdateAgentParams("private", provider = "cloud", model = "echo"))
        val agent = harness.store.agents().first { it.id == "private" }
        val capabilities = harness.capabilities(agent, following, null, server.runtime.capabilities)
        assertTrue(!capabilities.chat.actionable && "on-device" in capabilities.chat.reason.orEmpty(), capabilities.chat.toString())
        val failure = assertFails { call(Api.Send, SendParams("private", following, "tell the cloud")) }
        assertTrue("on-device" in failure.message.orEmpty(), failure.message)
        assertTrue(harness.store.entries("private", following).none { it is SessionEntry.UserMessage })

        agent("private", "local", "small")
        val pinned = hiddenSession("private", SessionSettings(provider = "local", model = "small"))
        admin.update(UpdateAgentParams("private", provider = "cloud", model = "echo"))
        call(Api.Send, SendParams("private", pinned, "still private"))
        until { harness.store.entries("private", pinned).any { it is SessionEntry.AssistantMessage } }
        assertEquals("small: ok", harness.store.entries("private", pinned).filterIsInstance<SessionEntry.AssistantMessage>().single().text)
    }

    @Test fun aHiddenDelegationKeepsItsOnDeviceModelWhenTheAgentDefaultChanges() = test {
        val parent = harness.newSession(harness.store.agents().first { it.id == "default" }).id
        val scope = CallerScope("default", parent)
        harness.configure(harness.store.agents().first { it.id == "default" }, parent, SessionSettings(approvals = "full"))
        val token = server.runtime.mcp.grant(scope.agentId, parent)!!.token
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", obj("name" to "delegate", "arguments" to obj("agent" to "private", "goal" to "x", "hidden" to true))) }
        server.runtime.mcpEndpoint.handle("Bearer $token", body.toString())
        val child = harness.store.sessions("private").single { it.hidden }
        assertEquals(SessionSettings(provider = "local", model = "small"), child.settings)
    }

    @Test fun aHiddenChildGetsNoEgressTools() = test {
        val parent = harness.newSession(harness.store.agents().first { it.id == "default" }).id
        harness.configure(harness.store.agents().first { it.id == "default" }, parent, SessionSettings(approvals = "full"))
        suspend fun delegate(hidden: Boolean) {
            val token = server.runtime.mcp.grant("default", parent)!!.token
            val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", obj("name" to "delegate", "arguments" to obj("agent" to "private", "goal" to "x", "hidden" to hidden))) }
            server.runtime.mcpEndpoint.handle("Bearer $token", body.toString())
        }
        delegate(hidden = false)
        val ordinary = advertised.getValue("local/small")
        assertTrue("web_fetch" in ordinary && "mcp_fake_search" in ordinary, ordinary.toString())
        delegate(hidden = true)
        val hidden = advertised.getValue("local/small")
        val egress = setOf("web_fetch", "web_search", "mcp_fake_search", "generate_image")
        assertTrue(hidden.none { it in egress || it.startsWith("browser") || it.startsWith("computer") }, hidden.toString())
    }

    @Test fun onlyNativeAgentsWithTheGroupGetTheManageToolsOverMcp() = test {
        suspend fun names(scope: CallerScope): Set<String> {
            val token = server.runtime.mcp.grant(scope.agentId, scope.sessionId!!)!!.token
            val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/list"); put("params", JsonObject(emptyMap())) }
            val reply = Json.parseToJsonElement(server.runtime.mcpEndpoint.handle("Bearer $token", body.toString()).body!!).jsonObject["result"]!!.jsonObject
            return reply["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        }
        val session = harness.newSession(harness.store.agents().first { it.id == "default" }).id
        val manage = setOf("agent_get", "agent_update", "agent_create", "mcp_list", "mcp_add", "mcp_remove", "mcp_test", "mcp_login", "session_configure")
        assertTrue(names(CallerScope("default", session)).containsAll(manage))
        assertTrue(names(CallerScope("external-agent", "external-session")).none { it in manage })
        admin.update(UpdateAgentParams("default", tools = config("default").tools - "agent_manage"))
        assertTrue(names(CallerScope("default", session)).none { it in manage })
    }
}
