package kinuvo.harness.server

import kinuvo.execution.api.*
import kinuvo.harness.server.execution.*
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class RemoteGatewayTest {
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
        val token = links.grant(ExposureGrant("friend", setOf("agent")))
        var disposed = false
        val gateway = RemoteGateway(links,
            { listOf(AgentInfo("agent", "Agent", "")) },
            { links.revoke("friend"); ExecutionSnapshot("created") },
            { _, id -> ExecutionSnapshot(id) },
            { _, _, _ -> }, { _, _ -> }, { _, _ -> true },
            { agent, id -> assertEquals("agent", agent); assertEquals("created", id); disposed = true })
        assertFailsWith<IllegalArgumentException> { gateway.call(token, RemoteExecutionCall("agent", action = "create")) }
        assertTrue(disposed)
        assertFalse(links.owns(token, "agent", "created"))
        assertTrue(links.grants().isEmpty())
    }

    @Test fun exposureIsCheckedForEveryOperation() = runTest {
        var persisted = RemoteSecrets()
        val links = RemoteLinks(persist = { persisted = it })
        val token = links.grant(ExposureGrant("friend", setOf("allowed", "cycle")))
        var creates = 0
        val gateway = RemoteGateway(links,
            { listOf(AgentInfo("allowed", "A", ""), AgentInfo("private", "B", ""), AgentInfo("cycle", "C", "", executionKind = ExecutionKind.Remote)) },
            { creates++; ExecutionSnapshot("source-session") },
            { agent, id -> require(agent == "allowed" && id == "source-session"); ExecutionSnapshot(id) },
            { _, _, _ -> }, { _, _ -> }, { _, _ -> true })
        assertEquals(listOf("allowed"), gateway.call(token, RemoteExecutionCall()).agents.map { it.id })
        assertFailsWith<IllegalArgumentException> { gateway.call(token, RemoteExecutionCall("private", action = "create")) }
        assertFailsWith<IllegalArgumentException> { gateway.call(token, RemoteExecutionCall("cycle", action = "create")) }
        assertEquals(0, creates)
        assertEquals("source-session", gateway.call(token, RemoteExecutionCall("allowed", action = "create")).snapshot?.sessionId)
        assertFailsWith<IllegalArgumentException> { gateway.call(token, RemoteExecutionCall("allowed", "other-session", "cancel")) }
        assertTrue(RemoteLinks(persisted).owns(token, "allowed", "source-session"))
        val otherToken = links.grant(ExposureGrant("other", setOf("allowed")))
        for (action in listOf("snapshot", "send", "cancel", "answer")) {
            assertFailsWith<IllegalArgumentException> { gateway.call(otherToken, RemoteExecutionCall("allowed", "source-session", action, text = "bad", requestId = "q", answer = kotlinx.serialization.json.JsonNull)) }
        }
        links.revoke("friend")
        assertFailsWith<IllegalArgumentException> { gateway.call(token, RemoteExecutionCall("allowed", "source-session", "snapshot")) }
    }

    @Test fun normalizedNullAnswerSurvivesRemoteWireDecoding() = runTest {
        val links = RemoteLinks()
        val token = links.grant(ExposureGrant("friend", setOf("agent")))
        links.claim(token, "agent", "session")
        var received: ExecutionAnswer? = null
        val gateway = RemoteGateway(links, { listOf(AgentInfo("agent", "Agent", "")) },
            { error("No create") }, { _, id -> ExecutionSnapshot(id) }, { _, _, _ -> }, { _, _ -> },
            { _, answer -> received = answer; true })
        val original = RemoteExecutionCall("agent", "session", "answer", requestId = "request", answer = JsonNull)
        val decoded = ProtocolJson.decodeFromString(RemoteExecutionCall.serializer(), ProtocolJson.encodeToString(RemoteExecutionCall.serializer(), original))
        assertTrue(gateway.call(token, decoded).accepted)
        assertEquals(ExecutionAnswer("session", "request", JsonNull), received)
    }

    @Test fun secretsNeverEnterCatalog() {
        var saved = RemoteSecrets()
        val links = RemoteLinks(persist = { saved = it })
        val token = links.grant(ExposureGrant("friend", setOf("agent")))
        links.save(SaveRemoteLink("host", "ws://127.0.0.1:9999/ws", token))
        assertEquals(setOf("agent"), RemoteLinks(saved).exposure(token))
        assertFalse(ProtocolJson.encodeToString(ExecutionCatalog.serializer(), ExecutionCatalog(emptyList(), emptyList())).contains(token))
    }
}
