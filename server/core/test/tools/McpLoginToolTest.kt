package kinuvo.server.tools

import kinuvo.harness.Agent
import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionFactory
import kinuvo.harness.ExecutionState
import kinuvo.harness.ToolSpec
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.ConfigScope
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.execution.AgentRegistration
import kinuvo.protocol.execution.HarnessConnection
import kinuvo.protocol.mcp.McpLogin
import kinuvo.protocol.mcp.McpLoginCompleteParams
import kinuvo.protocol.mcp.McpLoginParams
import kinuvo.protocol.mcp.McpParams
import kinuvo.protocol.mcp.McpServerInfo
import kinuvo.protocol.mcp.McpTestResult
import kinuvo.protocol.mcp.SaveMcpParams
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.Admin
import kinuvo.server.RuntimeFixture
import kinuvo.server.Runs
import kinuvo.server.plugin.McpManagement
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.ToolRun
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/** [McpLogin]'s own business logic (site lookup, verifying after sign-in, message wording), driven with a fake [ToolRun]. */
class McpLoginToolTest {
    private var test = McpTestResult(tools = listOf("search", "fetch"))
    private val mcp = object : McpManagement {
        override suspend fun mcpServers(agent: Agent) = listOf(McpServerInfo("server-1", url = "https://mcp.example.com/sse"))
        override suspend fun saveMcp(p: SaveMcpParams, createOnly: Boolean) = error("Not used")
        override suspend fun removeMcp(p: McpParams) = error("Not used")
        override suspend fun testMcp(p: McpParams) = test
        override suspend fun loginMcp(p: McpLoginParams): McpLogin = error("The client runs its own OAuth flow, never the server")
        override suspend fun completeMcpLogin(p: McpLoginCompleteParams) = error("Not used")
        override suspend fun logoutMcp(p: McpParams) = error("Not used")
        override suspend fun removeAgent(id: AgentId) {}
    }

    private class FakeRun(private val answer: InteractionAnswer.Connect) : ToolRun {
        var requested: Triple<AgentId, String, String?>? = null
        override val tools = emptyList<ToolSpec>()
        override suspend fun call(name: String, args: String) = error("Not used")
        override suspend fun connect(agentId: AgentId, server: String, scope: ConfigScope?, site: String?, reason: String?): InteractionAnswer.Connect {
            requested = Triple(agentId, server, site)
            return answer
        }
    }

    private fun args(name: String) = buildJsonObject { put("name", name) }

    private suspend fun mcpLoginTool(): Tool {
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test-mcp", "Test MCP")
            override val mcp: McpManagement = this@McpLoginToolTest.mcp
        }
        val fixture = RuntimeFixture(plugins = listOf(plugin))
        val harness = fixture.runtime()
        val runs = Runs(harness.log, CoroutineScope(EmptyCoroutineContext), object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
                error("Not used")
        })
        val admin = Admin(harness, runs) {}
        harness.agentManagement = AgentManagement(harness, admin)
        val management = harness.agentManagement!!
        val scope = AgentManageScope(fixture.agent.id, null)
        return AgentManageTools(management).tools(scope).first { it.descriptor.name == ToolNames.MCP_LOGIN }
    }

    @Test fun connectedSignInVerifiesAndReportsToolCount() = runTest {
        val tool = mcpLoginTool()
        val run = FakeRun(InteractionAnswer.Connect(true))
        val result = tool.execute(args("server-1"), "call-1", run)
        assertEquals("mcp.example.com", run.requested?.third)
        assertEquals("server-1", run.requested?.second)
        assertEquals("Signed in to 'server-1': 2 tools: search, fetch", result)
    }

    @Test fun verifyingFailsAfterSignIn() = runTest {
        test = McpTestResult(error = "still unauthorized")
        val result = mcpLoginTool().execute(args("server-1"), "call-1b", FakeRun(InteractionAnswer.Connect(true)))
        assertEquals("Signed in to 'server-1', but it still fails: still unauthorized", result)
    }

    @Test fun skippedSignInReportsSkip() = runTest {
        val result = mcpLoginTool().execute(args("server-1"), "call-2", FakeRun(InteractionAnswer.Connect(false)))
        assertEquals("The user skipped the sign-in.", result)
    }

    @Test fun failedSignInReportsError() = runTest {
        val result = mcpLoginTool().execute(args("server-1"), "call-3", FakeRun(InteractionAnswer.Connect(false, "denied")))
        assertEquals("Couldn't sign in to 'server-1': denied", result)
    }

    @Test fun unknownServerFailsWithoutAskingTheUser() = runTest {
        val run = FakeRun(InteractionAnswer.Connect(true))
        val result = mcpLoginTool().execute(args("missing"), "call-4", run)
        assertEquals("No MCP server named 'missing'; check mcp_list.", result)
        assertNull(run.requested)
    }
}
