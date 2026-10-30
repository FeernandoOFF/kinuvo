package kinuvo.harness.server

import kinuvo.harness.core.store.*
import kinuvo.harness.server.execution.*
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.*
import kotlin.uuid.Uuid

class ExecutionStorageTest {
    private val home = Path(SystemTemporaryDirectory, "execution-storage-${Uuid.random()}")
    @AfterTest fun cleanup() = deleteTree(home)

    @Test fun corruptMismatchedAndDuplicateSidecarsDoNotDisableHealthyRecovery() = runTest {
        val storage = ExecutionStorage(home)
        val connection = HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock")))
        storage.registry.save(connection)
        val registration = AgentRegistration("external", "External", "c", cwd = "/tmp")
        storage.registry.register(registration)
        val log = SessionLog(FileStore(AgentFiles(home)))
        val id = log.createSession("external", "").id
        val healthy = ExecutionRecovery("external", id, connection, registration, "g", checkpoint = listOf(SessionEntry.AssistantMessage("recovered", "m")))
        storage.save(healthy)
        val corrupt = Path(home, "agents", "external", "sessions", "broken.execution.json")
        writeAtomically(corrupt, "{broken")
        storage.save(healthy.copy(sessionId = "duplicate"))
        storage.save(healthy.copy(agentId = "other", sessionId = "duplicate"))
        val mismatch = Path(home, "agents", "external", "sessions", "wrong.execution.json")
        writeAtomically(mismatch, ProtocolJson.encodeToString(ExecutionRecovery.serializer(), healthy.copy(sessionId = "mismatched")))
        storage.save(healthy.copy(sessionId = "mismatched"))
        assertEquals(listOf(healthy), storage.load())
        assertEquals(3, storage.loadErrors.size)
        assertTrue(storage.loadErrors.any { "broken" in it })
        assertTrue(storage.loadErrors.any { "identity mismatch" in it })
        assertTrue(storage.loadErrors.any { "Duplicate" in it })
        val coordinator = ExecutionCoordinator(storage.registry, null, backgroundScope, log, storage)
        coordinator.recover()
        assertEquals("recovered", log.entries("external", id).filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        assertEquals(ExecutionPhase.Lost, coordinator.snapshot(id).phase)
        assertEquals("{broken", corrupt.readText())
        assertTrue(mismatch.exists())
        coordinator.close()
    }

    @Test fun migrationIsPerRecordIdempotentAndRetainsOrderAndExternalIdentity() = runBlocking {
        val storage = ExecutionStorage(home)
        storage.registry.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        storage.registry.register(AgentRegistration("external", "External", "c", cwd = "/tmp"))
        val legacy = listOf(
            ExternalSession("external", "c", ExecutionSnapshot("older", messages = listOf(ExecutionMessage("m", "assistant", "saved")), externalSessionId = "source")),
            ExternalSession("../escape", "c", ExecutionSnapshot("unsafe")),
            ExternalSession("external", "c", ExecutionSnapshot("newer")),
        )
        val oldFile = Path(home, "execution-sessions.json")
        val original = ProtocolJson.encodeToString(ListSerializer(ExternalSession.serializer()), legacy)
        writeAtomically(oldFile, original)
        val log = SessionLog(FileStore(AgentFiles(home)))
        repeat(2) { assertEquals(1, storage.migrate(log).size) }
        assertEquals(original, oldFile.readText())
        assertTrue(Path(home, "execution-sessions.legacy.json").exists())
        assertEquals("saved", log.entries("external", "older").filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        assertEquals("source", storage.load().first { it.sessionId == "older" }.externalSessionId)
        assertEquals(listOf("newer", "older"), storage.load().sortedBy { it.legacyOrder }.map { it.sessionId })
        assertFalse(AgentFiles(home).config("external").exists())
    }

    @Test fun unregisterPreservesArchiveAndProbePreservesSupport() = runBlocking {
        val registry = ExecutionStorage(home).registry
        registry.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        val registration = AgentRegistration("external", "External", "c", cwd = "/tmp")
        registry.register(registration)
        registry.negotiated("c", ExecutionCapabilities(resume = true, supportedMimeTypes = setOf("image/png")))
        registry.online("c")
        assertEquals(setOf("image/png"), registry.catalog().connections.single().capabilities.supportedMimeTypes)
        registry.removeAgent("external")
        assertNull(registry.registration("external"))
        assertTrue(registry.catalog().agents.single().archived)
        assertFailsWith<IllegalStateException> { registry.route("external") }
        assertTrue(ExecutionStorage(home).registry.catalog().agents.single().archived)
        registry.register(registration)
        assertEquals(registration, registry.registration("external"))
    }
}
