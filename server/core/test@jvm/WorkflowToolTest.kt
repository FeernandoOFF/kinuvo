package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.ToolTurn
import kinuvo.protocol.approval.*
import kinuvo.protocol.session.*
import kinuvo.server.tools.ToolScope
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkflowToolTest {
    @Test fun scriptedAgentSavesRunsAndReadsTheOutput() = runBlocking {
        val workflow = """{"id":"proof","name":"Proof","steps":[{"type":"condition","id":"c","left":"yes","op":"Equals","right":"yes"}]}"""
        TestServer.start(script = { on("save proven work") {
            toolCall("workflows", """{"action":"save","workflow":$workflow}""")
            toolCall("workflows", """{"action":"run","id":"proof"}""")
            reply("Finished")
        } }, scenario = { agent("work") }).use { server ->
            val agent = server.harness.store.agents().first { it.id.value == "work" }
            val session = server.startSession(agent.id, "save proven work")
            withTimeout(10000) {
                while (server.harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>().size < 2) {
                    server.harness.log.pending(session.id).filterIsInstance<PendingInteraction.Approval>().forEach {
                        server.harness.approvals.respond(it.id, ApprovalDecision.AllowOnce)
                    }
                    delay(20)
                }
            }
            val results = server.harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>()
            assertTrue(results.all { !it.isError }, results.toString())
            assertEquals("true", Json.parseToJsonElement(results.last().output).jsonObject["output"]?.jsonPrimitive?.content)
        }
    }

    @Test fun managementUsesTheGateAndReturnsValidationAndResults() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") { it.copy(approvals = "full") }; agent("research", "Research") }).use { server ->
            val session = server.startSession(agent.id)
            val gate = server.harness.composer.compose(agent, session.id).gate
            var approvals = 0
            val turn = ToolTurn(emit = { event -> if (event is AgentEvent.InteractionRequested) {
                val approval = event.interaction as PendingInteraction.Approval
                approvals++; assertFalse(approval.trustable)
                server.harness.approvals.respond(approval.id, ApprovalDecision.AllowOnce)
            } })
            suspend fun call(args: String) = gate.invoke(SessionEntry.ToolCall(ToolCallId("c$approvals"), "workflows", args), turn)
            val bad = call("""{"action":"save","workflow":{"id":"bad","name":"Bad","steps":[]}}""")
            assertTrue(bad.isError); assertContains(bad.output, "needs an id, name and steps")
            val beforeValidation = approvals
            val valid = call("""{"action":"validate","workflow":{"id":"validate-only","name":"Validate","steps":[{"type":"model","id":"m","prompt":"hello"}]}}""")
            assertFalse(valid.isError, valid.output)
            assertEquals("Valid", valid.output)
            val invalid = call("""{"action":"validate","workflow":{"id":"invalid","name":"Invalid","steps":[{"type":"text","id":"t","text":"{{steps.x.output}} {{input.missing}}"}],"edges":[{"from":"t","to":"zz"}]}}""")
            assertTrue(invalid.isError)
            assertContains(invalid.output, "unknown step zz")
            assertContains(invalid.output, "not an ancestor")
            assertContains(invalid.output, "undeclared input")
            assertEquals(beforeValidation, approvals)
            assertEquals("[]", call("""{"action":"list"}""").output)
            val saved = call("""{"action":"save","workflow":{"id":"test","name":"Test","steps":[{"type":"condition","id":"a","left":"2","op":"Equals","right":"2.0"}]}}""")
            assertFalse(saved.isError, saved.output)
            assertEquals("work", Json.parseToJsonElement(saved.output).jsonObject["agentId"]?.jsonPrimitive?.content)
            val run = call("""{"action":"run","id":"test"}""")
            assertFalse(run.isError, run.output)
            assertEquals("true", Json.parseToJsonElement(run.output).jsonObject["output"]?.jsonPrimitive?.content)
            val invalidAgent = call("""{"action":"validate","workflow":{"id":"bad-agent","name":"Bad Agent","agentId":"Research","steps":[{"type":"agent_turn","id":"turn-1","agentId":"Research","prompt":"test"}]}}""")
            assertTrue(invalidAgent.isError)
            val knownIds = server.harness.store.agents().take(20).joinToString { it.id.value }
            val hint = "unknown Agent 'Research'; known ids: $knownIds (use an id from the agents action, not a name)"
            assertContains(invalidAgent.output, "workflow agentId: $hint")
            assertContains(invalidAgent.output, "step turn-1: $hint")
            val before = approvals
            val agents = call("""{"action":"agents"}""")
            assertFalse(agents.isError, agents.output)
            val listed = Json.parseToJsonElement(agents.output).jsonArray.map { it.jsonObject }
            assertEquals(server.harness.store.agents().map { it.id.value }.toSet(), listed.map { it.getValue("id").jsonPrimitive.content }.toSet())
            for (stored in server.harness.store.agents()) {
                val item = listed.single { it.getValue("id").jsonPrimitive.content == stored.id.value }
                assertEquals(stored.name, item.getValue("name").jsonPrimitive.content)
                if (stored.id == agent.id) assertEquals(true, item["you"]?.jsonPrimitive?.boolean)
                else assertNull(item["you"])
            }
            assertFalse(call("""{"action":"list"}""").isError)
            val models = call("""{"action":"models","id":"test"}""")
            assertFalse(models.isError, models.output)
            val catalog = Json.parseToJsonElement(models.output).jsonObject
            assertNotNull(catalog["providers"])
            assertEquals(agent.config.model, catalog["default"]?.jsonObject?.get("model")?.jsonPrimitive?.content)
            val invalidModel = call("""{"action":"validate","workflow":{"id":"bad-model","name":"Bad","steps":[{"type":"agent_turn","id":"a","agentId":"work","prompt":"hi","model":{"provider":"missing","model":"nope"}}]}}""")
            assertTrue(invalidModel.isError)
            assertContains(invalidModel.output, "missing")
            assertContains(invalidModel.output, "models")
            assertEquals(before, approvals)
            val scoped = server.harness.composer.compose(agent, session.id, scope = ToolScope.only("memory")).gate
            assertTrue(scoped.invoke(SessionEntry.ToolCall(ToolCallId("scope"), "workflows", """{"action":"list"}"""), turn).isError)
            val unattended = server.harness.composer.compose(agent, sessionId = null, requireModel = false).gate
            val refused = unattended.invoke(SessionEntry.ToolCall(ToolCallId("recursive"), "workflows", """{"action":"run","id":"test"}"""), turn)
            assertTrue(refused.isError); assertContains(refused.output, "cannot start")
        }
    }
}
