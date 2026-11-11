package kinuvo.gateways.mcp

import arrow.core.raise.context.either
import kinuvo.harness.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.session.*
import kinuvo.server.tools.ToolScope
import kinuvo.testing.server.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class McpGatewayTest {
    @Test fun scopePolicyApprovalAuditAndRevocationUseTheRealServer() = runBlocking {
        lateinit var agent: Agent
        val output = "deployed" + "😀".repeat(5000) + "TAIL"
        val server = TestServer.start(plugins = listOf(FakeTools(
            FakeTool("deploy", Risk.High) { output }, FakeTool("other") { "other" },
        )), scenario = { agent = agent("work"); withoutDefaultAgent() })
        server.use {
            val session = server.startSession(agent.id)
            val composed = server.harness.compose(agent, session.id, scope = ToolScope.only("deploy"))
            val access = McpAccess()
            val endpoint = McpEndpoint(access)
            val run = McpAccess.Run(composed.tools, composed.gate, ToolTurn(emit = { event ->
                server.harness.log.publish(agent.id, session.id, event)
            }), SupervisorJob(), maxResultTokens = 100)
            val token = access.issue(run)
            fun request(method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params)
            }.toString()
            val listed = endpoint.handle("Bearer $token", request("tools/list"))
            val names = Json.parseToJsonElement(listed.body!!).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue("deploy" in names)
            assertFalse("other" in names)
            val call = async { endpoint.handle("Bearer $token", request("tools/call", buildJsonObject { put("name", "deploy") })) }
            val approval = withTimeout(5000) {
                while (true) {
                    server.harness.log.pending(session.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    yield()
                }
                error("unreachable")
            }
            assertTrue(server.harness.approvals.respond(approval.id, ApprovalDecision.AllowOnce))
            val response = call.await().body!!
            assertTrue(response.contains("deployed"))
            assertTrue(response.contains("TAIL"))
            assertTrue(response.contains("chars omitted"))
            assertTrue(response.length < 2000)
            assertTrue(server.harness.log.pending(session.id).isEmpty())
            access.revoke(run)
            assertEquals(McpError.InvalidCredential, either { access.resolve("Bearer $token") }.leftOrNull())
            assertEquals(401, endpoint.handle("Bearer $token", request("tools/list")).status)
        }
    }
}
