package kinuvo.testing.server

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kinuvo.testing.harness.awaitCondition
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.session.CatchUpParams
import kinuvo.protocol.approval.DenyRules
import kinuvo.protocol.session.HistoryParams
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.mcp.McpAppRef
import kinuvo.protocol.mcp.McpAppRequest
import kinuvo.protocol.mcp.McpAppToolRequest
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionParams
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.approval.approval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** One tool policy path: a chat run's call and an MCP App's call to the same tool meet the same Approvals and Deny rules. */
class ToolPolicyPathTest {
    private val deploy = FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" }
    private val appCall = "app-call"

    private fun KinuvoClient.entries(sessionId: String) =
        events(Api.Update).filter { it.sessionId == sessionId }.mapNotNull { (it.change as? SessionChange.Entry)?.entry }

    private suspend inline fun <reified E : SessionEntry> ReceiveTurbine<SessionEntry>.awaitEntry(): E {
        while (true) (awaitItem() as? E)?.let { return it }
    }

    private fun appRequest(session: SessionInfo) = McpAppToolRequest(McpAppRequest("work", session.id, appCall), "deploy")

    private fun JsonObject.text() = getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    @Test
    fun anApprovalIsAskedAnsweredAndCommittedForChatAndMcpAppCallsAlike() = runTest {
        lateinit var session: SessionInfo
        val server = TestServer.start(
            plugins = listOf(FakeTools(deploy)),
            script = { on("deploy") { toolCall("deploy"); reply("Done") } },
            scenario = {
                val work = agent("work")
                withoutDefaultAgent()
                session = session(work) { entry(SessionEntry.ToolResult(appCall, "deploy", "", app = McpAppRef("server", "ui://deploy", "hash"))) }
            },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val approvals = client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Event)?.event?.approval }
                approvals.test {
                    client.call(Api.CatchUp, CatchUpParams("work", session.id))
                    client.call(Api.Send, SendParams("work", session.id, "please deploy"))
                    val chat = awaitItem()
                    assertEquals("deploy", chat.toolCallName)
                    assertEquals("Pushes to production", chat.reason)
                    client.entries(session.id).test {
                        assertTrue(client.call(Api.Respond, RespondParams(session.id, chat.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                        assertEquals("deployed", awaitEntry<SessionEntry.ToolResult>().output)
                        assertEquals("Done", awaitEntry<SessionEntry.AssistantMessage>().text)
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitCondition("the run to end", 5.seconds) { client.call(Api.Session, SessionParams("work", session.id)).status == SessionStatus.Idle }

                    val app = async { client.call(Api.AppCallTool, appRequest(session)) }
                    val fromApp = awaitItem()
                    assertEquals("Pushes to production", fromApp.reason)
                    assertEquals(chat.identity, fromApp.identity)
                    assertTrue(client.call(Api.Respond, RespondParams(session.id, fromApp.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                    val result = app.await()
                    assertEquals("deployed", result.text())
                    assertEquals(false, result.getValue("isError").jsonPrimitive.boolean)
                    cancelAndIgnoreRemainingEvents()
                }
                val results = client.call(Api.History, HistoryParams("work", session.id, 100)).map { it.entry }.filterIsInstance<SessionEntry.ToolResult>()
                assertEquals(listOf(appCall, "call-0"), results.map { it.id }.sorted())
                assertEquals("deployed", results.single { it.id == "call-0" }.output)
            }
        }
    }

    @Test
    fun aDenyRuleBlocksChatAndMcpAppCallsWithoutAnApproval() = runTest {
        lateinit var session: SessionInfo
        val server = TestServer.start(
            plugins = listOf(FakeTools(deploy)),
            script = { on("deploy") { toolCall("deploy"); reply("Blocked") } },
            scenario = {
                val work = agent("work")
                withoutDefaultAgent()
                session = session(work) { entry(SessionEntry.ToolResult(appCall, "deploy", "", app = McpAppRef("server", "ui://deploy", "hash"))) }
            },
        )
        server.use {
            withContext(Dispatchers.Default) {
                server.harness.trustRules.update("work") { it.copy(deny = DenyRules(tools = listOf("deploy"))) }
                val client = server.client().also { it.connect() }
                val approvals = client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Event)?.event?.approval }
                approvals.test {
                    client.call(Api.CatchUp, CatchUpParams("work", session.id))
                    client.entries(session.id).test {
                        client.call(Api.Send, SendParams("work", session.id, "please deploy"))
                        val chat = awaitEntry<SessionEntry.ToolResult>()
                        assertTrue(chat.isError && "Deny rule" in chat.output, chat.output)
                        assertEquals("Blocked", awaitEntry<SessionEntry.AssistantMessage>().text)
                        cancelAndIgnoreRemainingEvents()
                    }
                    val result = client.call(Api.AppCallTool, appRequest(session))
                    assertTrue(result.getValue("isError").jsonPrimitive.boolean)
                    assertTrue("Deny rule" in result.text(), result.text())
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                assertNull(client.call(Api.History, HistoryParams("work", session.id, 100)).map { it.entry }.filterIsInstance<SessionEntry.ToolResult>().firstOrNull { it.output == "deployed" })
            }
        }
    }
}
