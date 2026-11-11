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
    @Test fun externalCallerCanSpawnAndWaitThroughServerTools() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = { reply("child answer") }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id)
            val composed = server.harness.compose(agent, session.id, scope = ToolScope.only("agents_spawn", "agents_wait"))
            val access = McpAccess()
            val endpoint = McpEndpoint(access)
            val run = McpAccess.Run(composed.tools, composed.gate, ToolTurn(emit = { server.harness.log.publish(agent.id, session.id, it) }), SupervisorJob())
            val token = access.issue(run)
            suspend fun call(name: String, args: JsonObject): String {
                val response = endpoint.handle("Bearer $token", buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                    putJsonObject("params") { put("name", name); put("arguments", args) }
                }.toString())
                assertEquals(200, response.status)
                return Json.parseToJsonElement(response.body!!).jsonObject.getValue("result").jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            }
            val spawned = Json.parseToJsonElement(call("agents_spawn", buildJsonObject { put("goal", "work") })).jsonObject
            val id = spawned.getValue("sessionId").jsonPrimitive.content
            val waiting = async { call("agents_wait", buildJsonObject { put("session_ids", JsonArray(listOf(JsonPrimitive(id)))); put("timeout_seconds", "5") }) }
            val review = withTimeout(5000) {
                while (true) {
                    server.harness.log.pending(session.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    yield()
                }
                error("unreachable")
            }
            assertTrue(review.args.contains("child answer"))
            assertEquals("child answer", review.preview?.body)
            server.harness.approvals.respond(review.id, ApprovalDecision.AllowOnce)
            val result = Json.parseToJsonElement(waiting.await()).jsonArray.single().jsonObject
            assertEquals(id, result.getValue("sessionId").jsonPrimitive.content)
            assertEquals("child answer", result.getValue("result").jsonPrimitive.content)
            access.revoke(run)
        }
    }

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
