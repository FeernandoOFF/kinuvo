package kinuvo.harness.server

import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.store.InMemoryStore
import kinuvo.harness.execution.api.*
import kinuvo.harness.server.execution.*
import kinuvo.harness.server.mcp.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentToolsMcpTest {
    private val fixture = RuntimeFixture()
    private val harness = fixture.runtime()
    private val asked = mutableListOf<String>()
    private var decision: ApprovalDecision = ApprovalDecision.AllowOnce
    private val gateway = AgentToolGateway(harness) { _, tool, _, _ -> asked += tool.descriptor.name; decision }
    private val access = McpAccess().apply { url = "http://127.0.0.1:1/mcp" }
    private val endpoint = McpEndpoint(access, gateway)

    private suspend fun session(approvals: String? = null): CallerScope {
        val info = harness.store.createSession("test", "")
        if (approvals != null) harness.store.append("test", info.id, SessionEntry.Settings(SessionSettings(approvals = approvals)))
        return CallerScope("test", info.id)
    }

    private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()

    private fun call(name: String) = rpc(1, "tools/call", buildJsonObject { put("name", name) })

    private fun McpReply.result() = Json.parseToJsonElement(body!!).jsonObject["result"]!!.jsonObject

    @Test fun lowRiskCallsRunWithoutAskingAtDefaultTolerance() = runTest {
        val scope = session()
        val token = access.grant(scope.agentId, scope.sessionId!!)!!.token
        val reply = endpoint.handle("Bearer $token", call("agents_list"))
        assertFalse(reply.result()["isError"]!!.jsonPrimitive.boolean)
        assertTrue("test" in reply.result()["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(asked.isEmpty())
    }

    @Test fun strictToleranceSendsEveryMcpCallThroughApproval() = runTest {
        val scope = session(approvals = "strict")
        val token = access.grant(scope.agentId, scope.sessionId!!)!!.token
        val allowed = endpoint.handle("Bearer $token", call("sessions_list"))
        assertEquals(listOf("sessions_list"), asked)
        assertFalse(allowed.result()["isError"]!!.jsonPrimitive.boolean)
        decision = ApprovalDecision.Deny("no")
        val denied = endpoint.handle("Bearer $token", call("sessions_list"))
        assertEquals(2, asked.size)
        assertTrue(denied.result()["isError"]!!.jsonPrimitive.boolean)
        assertTrue("denied" in denied.result()["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun serverDenyRulesBlockWithoutAsking() = runTest {
        fixture.rules = TrustRules(deny = DenyRules(tools = listOf("sessions_list")))
        val scope = session(approvals = "strict")
        val token = access.grant(scope.agentId, scope.sessionId!!)!!.token
        val blocked = endpoint.handle("Bearer $token", call("sessions_list"))
        assertTrue(blocked.result()["isError"]!!.jsonPrimitive.boolean)
        assertTrue(asked.isEmpty())
    }

    @Test fun listsToolsWithSchemas() = runTest {
        val scope = session()
        val token = access.grant(scope.agentId, scope.sessionId!!)!!.token
        val tools = endpoint.handle("Bearer $token", rpc(2, "tools/list")).result()["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("agents_list", "sessions_list", "sessions_search", "sessions_read", "session_status"), tools.map { it["name"]!!.jsonPrimitive.content }.toSet())
        val search = tools.first { it["name"]?.jsonPrimitive?.content == "sessions_search" }["inputSchema"]!!.jsonObject
        assertEquals("query", search["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun missingBadAndRevokedTokensAreRejected() = runTest {
        val scope = session()
        val granted = access.grant(scope.agentId, scope.sessionId!!)!!
        assertEquals(200, endpoint.handle("Bearer ${granted.token}", rpc(1, "ping")).status)
        assertEquals(401, endpoint.handle(null, rpc(1, "ping")).status)
        assertEquals(401, endpoint.handle("Bearer nope", rpc(1, "ping")).status)
        assertEquals(401, endpoint.handle(granted.token, rpc(1, "ping")).status)
        access.revoke(scope.sessionId!!)
        assertEquals(401, endpoint.handle("Bearer ${granted.token}", rpc(1, "ping")).status)
        assertNull(McpAccess().grant("a", "b"))
    }

    @Test fun tokensCarryTheirSessionsScopeAndNotAnotherAgents() = runTest {
        val mine = session()
        val other = harness.store.createSession("someone-else", "").id
        harness.store.append("someone-else", other, SessionEntry.UserMessage("private"))
        val token = access.grant(mine.agentId, mine.sessionId!!)!!.token
        val read = rpc(1, "tools/call", buildJsonObject { put("name", "sessions_read"); putJsonObject("arguments") { put("session_id", other) } })
        assertTrue(endpoint.handle("Bearer $token", read).result()["isError"]!!.jsonPrimitive.boolean)
    }

    @Test fun notificationsAreAcknowledgedWithoutAReply() = runTest {
        val scope = session()
        val token = access.grant(scope.agentId, scope.sessionId!!)!!.token
        val note = buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }.toString()
        assertEquals(202, endpoint.handle("Bearer $token", note).status)
    }

    @Test fun executionsAskTheUserInTheCallersSession() = runTest {
        val registry = ExecutionRegistry().also {
            it.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
            it.register(AgentRegistration("a", "Agent", "c", cwd = "/tmp"))
        }
        val mcp = McpAccess().apply { url = "http://127.0.0.1:1/mcp" }
        var granted: KinuvoMcp? = null
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) = error("Unused")
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope, tools: KinuvoMcp?): ExecutionSession {
                granted = tools
                return object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities(cancel = true)
                    override suspend fun prompt(input: ExecutionInput) = ExecutionAcceptance(null)
                    override suspend fun cancel() {}
                    override suspend fun answer(requestId: String, answer: JsonElement) {}
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, SessionLog(InMemoryStore()), mcp = mcp)
        val id = coordinator.create("a").id
        val token = granted!!.token
        assertEquals(CallerScope("a", id), mcp.resolve(token))
        val options = listOf(ExternalApprovalOption("allow", "Allow once", "allow_once"), ExternalApprovalOption("deny", "Deny", "reject_once"))
        val answer = async { coordinator.ask(id, "Kinuvo: sessions_list", "reads", options) }
        runCurrent()
        val request = coordinator.snapshot("a", id).pending.single()
        val prompt = assertIs<ExecutionPrompt.Approval>(request.prompt)
        assertEquals("Kinuvo: sessions_list", prompt.title)
        assertTrue(coordinator.answer(ExecutionAnswer(id, request.id, buildJsonObject { put("optionId", "allow") })))
        assertEquals("allow", answer.await())
        coordinator.closeExecution(id)
        assertNull(mcp.resolve(token))
    }
}
