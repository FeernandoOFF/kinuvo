package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.either
import kinuvo.harness.Agent
import kinuvo.server.tools.AgentManageScope
import kinuvo.server.tools.AgentManageTools
import kotlinx.serialization.json.jsonObject
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.Api
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.*
import kinuvo.protocol.session.*
import kinuvo.testing.server.TestServer
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.FakeModels
import kinuvo.testing.server.FakeModelProvider
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.capability.ExecutionLocation
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class SubAgentsTest {
    private suspend fun <T> value(block: suspend context(Raise<SubAgentError>) () -> T): T = either { block() }.getOrNull() ?: error("Sub-agent operation failed")

    @Test fun targetsResolveByIdOrUnambiguousNameAndUnknownLeavesNoChild() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("dev", "Developer") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val error = either { children.spawn(agent.id, parent.id, ToolCallId("bad"), AgentId("missing"), "task") }.leftOrNull()
            assertIs<SubAgentError.Target>(error)
            assertTrue(error.message.contains("dev (Developer)"))
            assertTrue(children.list(agent.id, parent.id).isEmpty())
            assertEquals(listOf(parent.id), server.harness.store.sessions(agent.id).map { it.id })
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("name"), AgentId("dEVELOPER"), "task") }
            assertEquals(agent.id, child.agentId)
            value { children.wait(agent.id, parent.id, listOf(child.sessionId), 5) }
            val catalog = children.tools(agent.id, parent.id).single { it.descriptor.name == "agents_list" }
                .execute(buildJsonObject {}, "list")
            val entry = ProtocolJson.parseToJsonElement(catalog).jsonObject.getValue("agents").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "dev" }.jsonObject
            assertEquals("dev", entry.getValue("id").jsonPrimitive.content)
            assertEquals("Developer", entry.getValue("name").jsonPrimitive.content)
            assertTrue(entry.getValue("on_device").jsonPrimitive.boolean)
        }
    }

    @Test fun resultsAreDurableAndBatchIdentityDoesNotDependOnCompletionOrder() = runBlocking {
        val home = Files.createTempDirectory("subagents").toFile()
        lateinit var agent: Agent
        lateinit var parent: SessionInfo
        lateinit var first: SubAgentState
        lateinit var second: SubAgentState
        try {
            TestServer.start(home = Path(home.path), script = {
                on("slow") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Continue?","kind":"text"}]}"""); reply("first answer") }
                on("fast") { reply("second answer") }
                on("follow up") { reply("continued") }
            }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
                parent = server.startSession(agent.id)
                val children = server.harness.subAgents!!
                first = value { children.spawn(agent.id, parent.id, ToolCallId("batch"), agent.id, "slow") }
                second = value { children.spawn(agent.id, parent.id, ToolCallId("batch"), agent.id, "fast") }
                val fast = value { children.wait(agent.id, parent.id, listOf(second.sessionId), 5) }.single()
                assertEquals("second answer", fast.result)
                val interaction = withTimeout(5000) {
                    while (true) {
                        server.harness.log.pending(parent.id).firstOrNull()?.let { return@withTimeout it }
                        yield()
                    }
                    error("unreachable")
                }
                val client = server.client(token = server.auth.sessionToken()); client.connect()
                assertTrue(client.call(Api.Respond, RespondParams(parent.id, interaction.id, InteractionAnswer.Questions(mapOf("ok" to listOf("yes"))))))
                val results = value { children.wait(agent.id, parent.id, listOf(first.sessionId, second.sessionId), 5) }.associateBy { it.sessionId }
                assertEquals("first answer", results[first.sessionId]?.result)
                assertEquals("second answer", results[second.sessionId]?.result)
                assertTrue(server.harness.log.page(agent.id, null, 100).sessions.none { it.id == first.sessionId || it.id == second.sessionId })
                value { children.send(agent.id, parent.id, first.sessionId, ToolCallId("follow"), "follow up") }
                assertEquals("continued", value { children.wait(agent.id, parent.id, listOf(first.sessionId), 5) }.single().result)
            }
            TestServer.start(home = Path(home.path)).use { restarted ->
                val results = value { restarted.harness.subAgents!!.wait(agent.id, parent.id, listOf(first.sessionId, second.sessionId), 0) }.associateBy { it.sessionId }
                assertEquals("continued", results[first.sessionId]?.result)
                assertEquals("second answer", results[second.sessionId]?.result)
            }
        } finally { home.deleteRecursively() }
    }

    @Test fun childApprovalIsAnsweredOnParentAndRunningFollowUpQueues() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("approval") { toolCall("agents_spawn", """{"goal":"nested"}"""); reply("approved turn done") }
            on("follow") { reply("follow done") }
        }, scenario = { agent = agent("work") }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("spawn"), agent.id, "approval") }
            val pending = withTimeout(5000) {
                while (true) {
                    server.harness.log.pending(parent.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    yield()
                }
                error("unreachable")
            }
            value { children.send(agent.id, parent.id, child.sessionId, ToolCallId("send"), "follow") }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, pending.id, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            withTimeout(5000) { while (children.list(agent.id, parent.id)[child.sessionId]?.result != "follow done") yield() }
            assertTrue(server.harness.log.pending(parent.id).isEmpty())
        }
    }

    @Test fun hiddenSessionsRequireLocalModelsAndPinTheirSettings() = runBlocking {
        lateinit var local: Agent
        lateinit var cloud: Agent
        val models = FakeModels(FakeModelProvider("test", "Models", listOf(
            ModelInfo("local", location = ExecutionLocation.ServerHost), ModelInfo("cloud", location = ExecutionLocation.External),
        )))
        TestServer.start(plugins = listOf(models, FakeTools(FakeTool("fixture_plugin"))), scenario = {
            local = agent("local") { it.copy(provider = "test", model = "local", approvals = "full") }
            cloud = agent("cloud") { it.copy(provider = "test", model = "cloud", approvals = "full") }
        }).use { server ->
            val parent = server.startSession(cloud.id)
            val children = server.harness.subAgents!!
            val regular = value { children.spawn(cloud.id, parent.id, ToolCallId("regular"), cloud.id, "task") }
            assertFalse(server.harness.store.session(cloud.id, regular.sessionId).hidden)
            assertFalse(regular.private)
            assertTrue(server.harness.composer.compose(cloud, regular.sessionId).tools.tools.any { it.name == "fixture_plugin" })
            children.tools(cloud.id, parent.id).single { it.descriptor.name == "agents_wait" }.execute(buildJsonObject {
                putJsonArray("session_ids") { add(regular.sessionId.value) }
            }, "regular-delivery")
            assertEquals(SubAgentStatus.Completed, value { children.wait(cloud.id, parent.id, listOf(regular.sessionId), 5) }.single().status)
            assertTrue(server.harness.log.pending(parent.id).isEmpty())
            assertTrue(server.harness.log.page(cloud.id, null, 100).sessions.none { it.id == regular.sessionId })
            val error = assertFailsWith<ServerException> { value { children.spawn(cloud.id, parent.id, ToolCallId("cloud"), cloud.id, "task", private = true) } }
            assertEquals(SubAgentError.Privacy(cloud.name), error.error)
            assertTrue(error.message.orEmpty().contains("has no on-device model"))
            val failed = children.list(cloud.id, parent.id).values.single { it.private }
            assertEquals(SubAgentStatus.Failed, failed.status)
            assertEquals(error.message, failed.result)
            assertEquals(failed, value { children.wait(cloud.id, parent.id, listOf(failed.sessionId), 0) }.single())
            val child = value { children.spawn(cloud.id, parent.id, ToolCallId("local"), local.id, "task", private = true) }
            assertEquals(SubAgentStatus.Completed, value { children.wait(cloud.id, parent.id, listOf(child.sessionId), 5) }.single().status)
            assertFailsWith<IllegalArgumentException> { server.harness.configure(local, child.sessionId, SessionSettings(provider = "test", model = "cloud")) }
            val configure = AgentManageTools(server.harness.agentManagement!!).tools(AgentManageScope(local.id, child.sessionId), hidden = true).single()
            assertFailsWith<IllegalArgumentException> { configure.preview(ProtocolJson.parseToJsonElement("""{"provider":"test","model":"cloud"}""").jsonObject) }
            val composed = server.harness.composer.compose(local, child.sessionId)
            assertTrue(composed.tools.tools.none { it.name == "fixture_plugin" })
            assertTrue(composed.tools.tools.none { it.name in setOf("memory", "web_fetch", "generate_image", "run_code", "agent_get", "agent_update", "agent_create", "mcp_list", "mcp_add", "mcp_remove", "mcp_test", "mcp_login") })
            val waitTool = children.tools(cloud.id, parent.id).single { it.descriptor.name == "agents_wait" }
            val delivery = async { waitTool.execute(buildJsonObject {
                putJsonArray("session_ids") { add(child.sessionId.value) }
            }, "delivery") }
            val approval = withTimeout(5000) {
                while (true) {
                    server.harness.log.pending(parent.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    yield()
                }
                error("unreachable")
            }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            assertTrue(delivery.await().contains("withheld"))
            assertTrue(server.harness.store.sessions(local.id).none { it.id == child.sessionId })
            assertEquals(SubAgentError.Unknown(child.sessionId), either {
                children.send(cloud.id, parent.id, child.sessionId, ToolCallId("deleted"), "again")
            }.leftOrNull())
            server.harness.log.deleteSession(cloud.id, parent.id)
            assertTrue(server.harness.store.sessions(cloud.id).none { it.id == regular.sessionId })

        }
    }

    @Test fun parentCancellationChildCancellationDepthAndSharedBudget() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val first = value { children.spawn(agent.id, parent.id, ToolCallId("one"), agent.id, "hold") }
            val grandchild = value { children.spawn(agent.id, first.sessionId, ToolCallId("two"), agent.id, "hold") }
            assertEquals(SubAgentError.Depth, either { children.spawn(agent.id, grandchild.sessionId, ToolCallId("three"), agent.id, "hold") }.leftOrNull())
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            client.call(Api.Stop, StopParams(first.sessionId))
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, parent.id, listOf(first.sessionId), 5) }.single().status)
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, first.sessionId, listOf(grandchild.sessionId), 5) }.single().status)
            val next = value { children.spawn(agent.id, parent.id, ToolCallId("next"), agent.id, "hold") }
            try { children.consume(parent.id); repeat(64) { children.consume(next.sessionId) }; fail("Budget should cancel the tree") } catch (_: CancellationException) { }
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, parent.id, listOf(next.sessionId), 5) }.single().status)
            val activeParent = server.startSession(agent.id, "hold")
            val activeChild = value { children.spawn(agent.id, activeParent.id, ToolCallId("active"), agent.id, "hold") }
            client.call(Api.Stop, StopParams(activeParent.id))
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, activeParent.id, listOf(activeChild.sessionId), 5) }.single().status)
        }
    }
}
