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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentManageServerTest {
    private fun args(text: String) = ProtocolJson.parseToJsonElement(text).jsonObject

    @Test fun previewAndAlwaysAskSurviveFullToleranceAndForgedTrust() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.compose(agent, session.id)
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
            val composed = server.harness.compose(agent, session.id)
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
            override suspend fun saveMcp(p: SaveMcpParams): McpServerInfo {
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
        }
        val plugin = object : HarnessPlugin {
            override val definition = PluginDefinition("test.mcp", "Test MCP")
            override val mcp = mcp
        }
        TestServer.start(plugins = listOf(plugin), scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.compose(agent, session.id)
            val requests = mutableListOf<PendingInteraction.Approval>()
            val turn = ToolTurn(emit = { event ->
                val form = event.questions
                if (form != null) {
                    assertTrue(form.questions.single().label.contains("https://auth.example/sign-in"))
                    server.harness.questions.respond(form.id, mapOf("address" to listOf("http://localhost/callback?code=1")))
                }
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
            assertEquals("Signed in to 'shared'.", call("mcp_login", """{"name":"shared"}""").output)
            assertEquals(listOf("http://localhost/callback?code=1"), completed)
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
            assertTrue(server.harness.compose(restricted, session.id).tools.tools.none { it.name == "agent_get" })
            assertTrue(server.harness.compose(agent, session.id, scope = ToolScope.only("agent_manage")).tools.tools.any { it.name == "agent_get" })
        }
    }
}
