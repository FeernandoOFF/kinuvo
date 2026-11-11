package kinuvo.server

import kinuvo.harness.*
import kinuvo.server.execution.*
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class RemoteGatewayTest {
    @Test fun surfaceActionSurvivesRemoteWireAndSend() = runTest {
        val links = RemoteLinks()
        val token = links.grant(ExposureGrant("friend", setOf(AgentId("agent"))))
        links.claim(token, AgentId("agent"), SessionId("session"))
        val context = buildJsonObject { put("name", "Alex") }
        val action = SurfaceAction(ToolCallId("call"), "plan", "save", "submit", context, context)
        var received: SurfaceAction? = null
        val gateway = RemoteGateway(links, { listOf(AgentInfo(AgentId("agent"), "Agent", "")) },
            { error("No create") }, { _, id -> ExecutionSnapshot(SessionId(id)) },
            { _, _, text, value -> assertEquals("Save plan", text); received = value },
            { _, _ -> }, { _, _ -> true })
        val original = RemoteExecutionCall(AgentId("agent"), SessionId("session"), "send", "Save plan", surfaceAction = action)
        val decoded = ProtocolJson.decodeFromString(RemoteExecutionCall.serializer(), ProtocolJson.encodeToString(RemoteExecutionCall.serializer(), original))
        gateway.call(token, decoded)
        assertEquals(action, received)
    }

    @Test fun cleartextRemoteLinksPersistAndStillValidateAddresses() {
        var stored = RemoteSecrets()
        val links = RemoteLinks(persist = { stored = it })
        val link = SaveRemoteLink("studio", "ws://studio.example.com:8787/ws", "code")
        links.save(link)
        assertEquals(link, RemoteLinks(stored).link("studio"))
        assertFailsWith<IllegalArgumentException> { links.save(link.copy(url = "ws://user:password@example.com/ws")) }
        assertEquals(link, links.link("studio"))
    }

    @Test fun revokeDuringCreateDisposesUnclaimedExecution() = runTest {
        val links = RemoteLinks()
        val token = links.grant(ExposureGrant("friend", setOf(AgentId("agent"))))
        var disposed = false
        val gateway = RemoteGateway(links,
            { listOf(AgentInfo(AgentId("agent"), "Agent", "")) },
            { links.revoke("friend"); ExecutionSnapshot(SessionId("created")) },
            { _, id -> ExecutionSnapshot(SessionId(id)) },
            { _, _, _, _ -> }, { _, _ -> }, { _, _ -> true },
            { agent, id -> assertEquals("agent", agent); assertEquals("created", id); disposed = true })
        assertFailsWith<IllegalArgumentException> { gateway.call(token, RemoteExecutionCall(AgentId("agent"), action = "create")) }
        assertTrue(disposed)
        assertFalse(links.owns(token, AgentId("agent"), SessionId("created")))
        assertTrue(links.grants().isEmpty())
    }

    @Test fun exposureIsCheckedForEveryOperation() = runTest {
        var persisted = RemoteSecrets()
        val links = RemoteLinks(persist = { persisted = it })
        val token = links.grant(ExposureGrant("friend", setOf(AgentId("allowed"), AgentId("cycle"))))
        var creates = 0
        val gateway = RemoteGateway(links,
            { listOf(AgentInfo(AgentId("allowed"), "A", ""), AgentInfo(AgentId("private"), "B", ""), AgentInfo(AgentId("cycle"), "C", "", executionKind = ExecutionKind.Remote)) },
            { creates++; ExecutionSnapshot(SessionId("source-session")) },
            { agent, id -> require(agent == "allowed" && id == "source-session"); ExecutionSnapshot(SessionId(id)) },
            { _, _, _, _ -> }, { _, _ -> }, { _, _ -> true })
        assertEquals(listOf("allowed"), gateway.call(token, RemoteExecutionCall()).agents.map { it.id.value })
        assertFailsWith<ServerError.Forbidden> { gateway.call(token, RemoteExecutionCall(AgentId("private"), action = "create")) }
        assertFailsWith<ServerError.Forbidden> { gateway.call(token, RemoteExecutionCall(AgentId("cycle"), action = "create")) }
        assertEquals(0, creates)
        assertEquals("source-session", gateway.call(token, RemoteExecutionCall(AgentId("allowed"), action = "create")).snapshot?.sessionId?.value)
        assertFailsWith<ServerError.Forbidden> { gateway.call(token, RemoteExecutionCall(AgentId("allowed"), SessionId("other-session"), "cancel")) }
        assertTrue(RemoteLinks(persisted).owns(token, AgentId("allowed"), SessionId("source-session")))
        val otherToken = links.grant(ExposureGrant("other", setOf(AgentId("allowed"))))
        for (action in listOf("snapshot", "send", "cancel", "answer")) {
            assertFailsWith<ServerError.Forbidden> { gateway.call(otherToken, RemoteExecutionCall(AgentId("allowed"), SessionId("source-session"), action, text = "bad", requestId = "q", answer = InteractionAnswer.Harness(JsonNull))) }
        }
        links.revoke("friend")
        assertFailsWith<ServerError.Forbidden> { gateway.call(token, RemoteExecutionCall(AgentId("allowed"), SessionId("source-session"), "snapshot")) }
    }

    @Test fun normalizedNullAnswerSurvivesRemoteWireDecoding() = runTest {
        val links = RemoteLinks()
        val token = links.grant(ExposureGrant("friend", setOf(AgentId("agent"))))
        links.claim(token, AgentId("agent"), SessionId("session"))
        var received: RespondParams? = null
        val gateway = RemoteGateway(links, { listOf(AgentInfo(AgentId("agent"), "Agent", "")) },
            { error("No create") }, { _, id -> ExecutionSnapshot(SessionId(id)) }, { _, _, _, _ -> }, { _, _ -> },
            { _, answer -> received = answer; true })
        val original = RemoteExecutionCall(AgentId("agent"), SessionId("session"), "answer", requestId = "request", answer = InteractionAnswer.Harness(JsonNull))
        val decoded = ProtocolJson.decodeFromString(RemoteExecutionCall.serializer(), ProtocolJson.encodeToString(RemoteExecutionCall.serializer(), original))
        assertTrue(gateway.call(token, decoded).accepted)
        assertEquals(RespondParams(SessionId("session"), "request", InteractionAnswer.Harness(JsonNull)), received)
    }

    @Test fun secretsNeverEnterCatalog() {
        var saved = RemoteSecrets()
        val links = RemoteLinks(persist = { saved = it })
        val token = links.grant(ExposureGrant("friend", setOf(AgentId("agent"))))
        links.save(SaveRemoteLink("host", "ws://127.0.0.1:9999/ws", token))
        assertEquals(setOf(AgentId("agent")), RemoteLinks(saved).exposure(token))
        assertFalse(ProtocolJson.encodeToString(ExecutionCatalog.serializer(), ExecutionCatalog(emptyList(), emptyList())).contains(token))
    }
}
