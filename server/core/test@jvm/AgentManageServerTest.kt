package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.ToolTurn
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.mcp.*
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.protocol.session.*
import kinuvo.server.plugin.*
import kinuvo.server.tools.*
import kinuvo.testing.server.TestServer
import kinuvo.testing.server.FakeMcpManagement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentManageServerTest {
    private fun args(text: String) = ProtocolJson.parseToJsonElement(text).jsonObject

    @Test fun presetsListAddAndFollowRiskTolerance() = runBlocking {
        lateinit var agent: Agent
        val entries = mutableListOf(
            McpCatalog.first { it.id == "linear" }.server(ConfigScope.Shared),
            McpServerInfo("my-notes", ConfigScope.Agent, url = "https://mcp.notion.com/mcp/"),
        )
        val mcp = FakeMcpManagement(entries)
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.mcp", "Test MCP")
            override val mcp = mcp
        }
        TestServer.start(plugins = listOf(plugin), scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.composer.compose(agent, session.id)
            val requests = mutableListOf<PendingInteraction.Approval>()
            val turn = ToolTurn(emit = { event -> event.approval?.let { approval ->
                requests += approval
                assertFalse(approval.trustable)
                server.harness.approvals.respond(approval.id, ApprovalDecision.AllowOnce)
            } })
            var index = 0
            suspend fun call(name: String, text: String = "{}") = server.harness.composer.compose(agent, session.id).gate.invoke(
                SessionEntry.ToolCall(ToolCallId("preset-${index++}"), name, text), turn)
            val available = call("mcp_list").output.substringAfter("Available presets:")
            assertTrue("todoist: Todoist (OAuth)" in available)
            assertTrue("github: GitHub (Token)" in available)
            assertTrue("gmail: Gmail (OwnClient)" in available)
            assertFalse("linear:" in available || "notion:" in available)
            val add = composed.tools.tools.first { it.name == "mcp_add" }
            assertTrue("preset" in add.schema["properties"]!!.jsonObject)
            assertFalse(call("mcp_add", """{"preset":"todoist"}""").isError)
            assertTrue(requests.isEmpty())
            assertEquals("https://ai.todoist.net/mcp", entries.last().url)
            assertEquals("todoist", entries.last().name)
            assertEquals(ConfigScope.Agent, entries.last().scope)
            assertFalse("todoist:" in call("mcp_list").output.substringAfter("Available presets:"))
            for (id in listOf("github", "gmail")) {
                val error = call("mcp_add", """{"preset":"$id"}""")
                assertTrue(error.isError)
                assertTrue("Settings → Tools & MCP → MCP servers" in error.output)
                assertTrue((if (id == "github") "personal access token" else "OAuth client ID") in error.output)
            }
            for (text in listOf("""{"preset":"missing"}""", """{"preset":"todoist","url":"https://evil.example"}"""))
                assertTrue(call("mcp_add", text).isError)
            assertTrue(requests.isEmpty())
            for (tolerance in listOf("strict", "default", "relaxed", "full")) {
                server.harness.configure(agent, session.id, SessionSettings(approvals = tolerance))
                entries.removeAll { it.name == "atlassian" }
                val before = requests.size
                assertFalse(call("mcp_add", """{"preset":"atlassian"}""").isError)
                assertEquals(before + if (tolerance == "full") 0 else 1, requests.size)
                assertFalse(call("mcp_add", """{"name":"custom","url":"https://example.com/mcp"}""").isError)
                assertEquals(before + if (tolerance == "full") 1 else 2, requests.size)
            }
        }
    }

    @Test fun presetsAreCreateOnlyAndListEffectiveConnections() = runBlocking {
        lateinit var agent: Agent
        val todoist = McpCatalog.first { it.id == "todoist" }.server().copy(
            auth = McpAuthStatus.SignedIn, includeTools = listOf("read"), excludeTools = listOf("delete"))
        val entries = mutableListOf(todoist)
        val mcp = FakeMcpManagement(entries)
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.mcp", "Test MCP")
            override val mcp = mcp
        }
        TestServer.start(plugins = listOf(plugin), scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val turn = ToolTurn(emit = { assertFalse(it is AgentEvent.InteractionRequested) })
            var index = 0
            suspend fun call(name: String, text: String = "{}") = server.harness.composer.compose(agent, session.id).gate.invoke(
                SessionEntry.ToolCall(ToolCallId("create-${index++}"), name, text), turn)
            suspend fun reject(existing: McpServerInfo) {
                entries.clear()
                entries += existing
                val result = call("mcp_add", """{"preset":"todoist"}""")
                assertTrue(result.isError, result.output)
                assertTrue("already configured" in result.output && "mcp_list" in result.output)
                assertEquals(listOf(existing), entries)
            }
            reject(todoist)
            reject(todoist.copy(name = "signed-in-alias", url = todoist.url + "/"))
            for (url in listOf("https://AI.TODOIST.NET/mcp", "https://ai.todoist.net:443/mcp", "HTTPS://AI.TODOIST.NET:443/mcp/")) {
                reject(todoist.copy(name = "alias", url = url))
                assertFalse("todoist:" in call("mcp_list").output.substringAfter("Available presets:"))
            }
            reject(todoist.copy(url = "https://other.example/mcp"))
            reject(todoist.copy(scope = ConfigScope.Shared, url = "https://other.example/mcp", agents = listOf(AgentId("other"))))
            reject(todoist.copy(scope = ConfigScope.Shared, name = "shared-alias"))
            entries.clear()
            entries += McpCatalog.first { it.id == "linear" }.server(ConfigScope.Shared)
            entries += McpServerInfo("linear", ConfigScope.Agent, url = "https://other.example/mcp")
            entries += McpCatalog.first { it.id == "notion" }.server(ConfigScope.Shared).copy(name = "other-notes", agents = listOf(AgentId("other")))
            val available = call("mcp_list").output.substringAfter("Available presets:")
            assertTrue("linear: Linear" in available && "unavailable: name already configured" in available)
            assertTrue("notion: Notion" in available)
            entries += McpServerInfo("templated", ConfigScope.Agent, url = "https://example.com:\${MCP_PORT}/mcp")
            assertTrue("notion: Notion" in call("mcp_list").output)
            assertFalse(call("mcp_add", """{"preset":"notion"}""").isError)
            assertFalse(call("mcp_add", """{"preset":"todoist"}""").isError)
        }
    }

    @Test fun noPresetsWithoutMcpBackend() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.composer.compose(agent, session.id)
            val result = composed.gate.invoke(
                SessionEntry.ToolCall(ToolCallId("list"), "mcp_list", "{}"),
                ToolTurn(emit = { assertFalse(it is AgentEvent.InteractionRequested) }))
            assertFalse(result.isError, result.output)
            assertEquals("None.", result.output.substringAfter("Available presets:").trim())
        }
    }

    @Test fun previewAndAlwaysAskSurviveFullToleranceAndForgedTrust() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.composer.compose(agent, session.id)
            val requests = mutableListOf<PendingInteraction.Approval>()
            val turn = ToolTurn(emit = { event ->
                if (event is AgentEvent.InteractionRequested) {
                    val approval = event.interaction as PendingInteraction.Approval
                    requests += approval
                    assertFalse(approval.trustable)
                    server.harness.approvals.respond(approval.id, ApprovalDecision.AlwaysAllow)
                }
            })
            repeat(2) { index ->
                val result = composed.gate.invoke(SessionEntry.ToolCall(ToolCallId("c$index"), "agent_update", """{"name":"New $index"}"""), turn)
                assertFalse(result.isError, result.output)
            }
            assertEquals(2, requests.size)
            assertTrue(requests.first().preview!!.text().contains("Name: work → New 0"))
            assertTrue(server.harness.trustRules.get(agent.id).trusted.isEmpty())
            val created = composed.gate.invoke(SessionEntry.ToolCall(ToolCallId("create"), "agent_create", """{"name":"Helper","soul":"Be brief."}"""), turn)
            assertFalse(created.isError, created.output)
            assertEquals("New Agent:\n- Agent: (none) → Helper (helper), blank: default settings, no Memory, Skills, MCP servers or rules from you\n- Soul: (none) → Be brief.", requests.last().preview!!.text())
            val helper = server.harness.store.agents().single { it.id == AgentId("helper") }
            assertEquals(AgentConfig().approvals, helper.config.approvals)
        }
    }

    @Test fun forbiddenChangesFailBeforeAnyApproval() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") { it.copy(tools = listOf("agent_manage", "memory")) } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.composer.compose(agent, session.id)
            val turn = ToolTurn(emit = { event -> assertFalse(event is AgentEvent.InteractionRequested) })
            for (name in listOf("agent_update", "agent_create", "session_configure", "mcp_add", "mcp_remove")) {
                for (field in listOf("approvals", "risk_tolerance", "tolerance", "rules", "trust", "trusted", "deny", "exec", "workspace", "cloneFrom", "agentId", "scope")) {
                    if (name == "session_configure" && field == "approvals") continue
                    val result = composed.gate.invoke(SessionEntry.ToolCall(ToolCallId("$name-$field"), name, """{"$field":"forbidden"}"""), turn)
                    assertTrue(result.isError, "$name $field")
                    assertTrue(result.output.contains("Settings"), result.output)
                }
            }
            val result = composed.gate.invoke(SessionEntry.ToolCall(ToolCallId("unavailable"), "agent_update", """{"enable_tools":"browser"}"""), turn)
            assertTrue(result.isError)
            server.harness.configure(agent, session.id, SessionSettings(approvals = "strict"))
            val raised = composed.gate.invoke(SessionEntry.ToolCall(ToolCallId("raised"), "session_configure", """{"approvals":"full"}"""), turn)
            assertTrue(raised.isError)
        }
    }

    @Test fun mcpChangesAreOwnOnlyAndSecretsNeverAppearInApproval() = runBlocking {
        lateinit var agent: Agent
        val entries = mutableListOf(McpServerInfo("shared", ConfigScope.Shared))
        val changes = mutableListOf<AgentId>()
        val completed = mutableListOf<String>()
        val mcp = object : McpManagement by McpManagement.Unavailable {
            override suspend fun mcpServers(agent: Agent) = entries.toList()
            override suspend fun saveMcp(p: SaveMcpParams, createOnly: Boolean): McpServerInfo {
                if (createOnly) entries.requireNewServer(p)
                changes += p.agentId
                assertEquals(ConfigScope.Agent, p.server.scope)
                entries += p.server
                return p.server
            }
            override suspend fun removeMcp(p: McpParams) {
                changes += p.agentId
                assertEquals(ConfigScope.Agent, p.scope)
                entries.removeAll { it.name == p.name && it.scope == p.scope }
            }
            override suspend fun loginMcp(p: McpLoginParams) = McpLogin("https://auth.example/sign-in")
            override suspend fun completeMcpLogin(p: McpLoginCompleteParams) = McpServerInfo("shared", ConfigScope.Shared).also { completed += p.callbackUrl }
            override suspend fun testMcp(p: McpParams) = McpTestResult(tools = listOf("search"))
        }
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.mcp", "Test MCP")
            override val mcp = mcp
        }
        TestServer.start(plugins = listOf(plugin), scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.composer.compose(agent, session.id)
            val requests = mutableListOf<PendingInteraction.Approval>()
            val turn = ToolTurn(emit = { event ->
                val connect = event.connect
                if (connect != null) server.harness.connects.respond(connect.id, InteractionAnswer.Connect(true))
                val approval = event.approval
                if (approval != null) {
                    requests += approval
                    assertFalse(approval.toString().contains("secret-value"))
                    assertFalse(approval.trustable)
                    server.harness.approvals.respond(approval.id, ApprovalDecision.AllowOnce)
                }
            })
            suspend fun call(name: String, text: String) = composed.gate.invoke(SessionEntry.ToolCall(ToolCallId(name), name, text), turn)
            assertFalse(call("mcp_add", """{"name":"own","url":"https://example.com/mcp","env":{"TOKEN":"secret-value"},"headers":{"Authorization":"secret-value"}}""").isError)
            assertFalse(call("mcp_remove", """{"name":"own"}""").isError)
            assertTrue(call("mcp_remove", """{"name":"shared"}""").isError)
            assertEquals(2, requests.size)
            assertEquals("Adds an MCP server to work:\n- MCP server: (none) → own [https://example.com/mcp]\n- Environment: (none) → TOKEN\n- Headers: (none) → Authorization\nEnvironment and header values are not shown.", requests.first().preview!!.text())
            assertEquals(listOf(agent.id, agent.id), changes)
            assertEquals("Signed in to 'shared': 1 tools: search", call("mcp_login", """{"name":"shared"}""").output)
            assertTrue(completed.isEmpty())
            assertEquals("shared", entries.single().name)
        }
    }

    @Test fun hiddenInventoryAndExplicitOptIn() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") }).use { server ->
            val tools = AgentManageTools(server.harness.agentManagement!!)
                .tools(AgentManageScope(agent.id, null), hidden = true)
            assertEquals(listOf("session_configure"), tools.map { it.descriptor.name })
            val session = server.startSession(agent.id)
            val restricted = agent.copy(config = agent.config.copy(tools = listOf("memory")))
            assertTrue(server.harness.composer.compose(restricted, session.id).tools.tools.none { it.name == "agent_get" })
            assertTrue(server.harness.composer.compose(agent, session.id, scope = ToolScope.only("agent_manage")).tools.tools.any { it.name == "agent_get" })
        }
    }
}
