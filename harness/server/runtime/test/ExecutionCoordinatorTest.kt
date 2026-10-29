package kinuvo.harness.server

import kinuvo.execution.api.*
import kinuvo.harness.server.execution.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionCoordinatorTest {
    @Test fun failedOldPromptCannotCloseResumedGeneration() = runTest {
        val registry = ExecutionRegistry()
        registry.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        registry.register(AgentRegistration("a", "Agent", "c", cwd = "/tmp"))
        val states = mutableListOf<ExecutionState>()
        val failure = CompletableDeferred<Unit>()
        var replacementClosed = false
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                states += state
                state.reference("external")
                val replacement = states.size > 1
                return object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities(resume = true)
                    override suspend fun prompt(text: String) = withContext(NonCancellable) {
                        failure.await()
                        error("Old prompt failed after reconnect")
                    }
                    override suspend fun cancel() {}
                    override suspend fun answer(requestId: String, answer: JsonElement) {}
                    override suspend fun close() { if (replacement) replacementClosed = true }
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope)
        val id = coordinator.create("a").id
        coordinator.send(SendParams("a", id, "hello"))
        runCurrent()
        states.first().lost()
        runCurrent()
        coordinator.resume(id)
        failure.complete(Unit)
        runCurrent()
        assertEquals(ExecutionPhase.Idle, coordinator.snapshot(id).phase)
        assertTrue(coordinator.agentInUse("a"))
        assertFalse(replacementClosed)
        coordinator.closeExecution(id)
    }

    @Test fun failedResumeRetainsTranscriptAndReleasesAgent() = runTest {
        val registry = ExecutionRegistry()
        registry.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        registry.register(AgentRegistration("a", "Agent", "c", cwd = "/tmp"))
        val saved = ExecutionSnapshot("local", messages = listOf(ExecutionMessage("m", "user", "saved")), externalSessionId = "external")
        var attempts = 0
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                attempts++
                assertEquals("external", state.snapshot().externalSessionId)
                error("ACP session/resume is unsupported")
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, listOf(ExternalSession("a", "c", saved)))
        repeat(2) {
            assertFailsWith<IllegalStateException> { coordinator.resume("local") }
            assertEquals(saved.messages, coordinator.snapshot("local").messages)
            assertEquals(ExecutionPhase.Lost, coordinator.snapshot("local").phase)
            assertFalse(coordinator.agentInUse("a"))
        }
        assertEquals(2, attempts)
        registry.removeAgent("a")
        coordinator.closeExecution("local")
        assertEquals(saved.messages, coordinator.snapshot("local").messages)
    }

    @Test fun toolHistoryPreservesCompletionAndFailure() = runTest {
        val messages = listOf("in_progress", "completed", "failed").map { status ->
            ExecutionMessage("tool:$status", "tool", "Search", buildJsonObject {
                put("toolCallId", status)
                put("status", status)
                putJsonObject("rawInput") { put("query", "test") }
                if (status == "completed") put("rawOutput", "Found it")
            })
        }
        val coordinator = ExecutionCoordinator(ExecutionRegistry(), null, backgroundScope,
            restored = listOf(ExternalSession("a", "c", ExecutionSnapshot("s", messages = messages))))
        val entries = coordinator.history("a", "s").map { it.entry }
        val calls = entries.filterIsInstance<SessionEntry.ToolCall>()
        val results = entries.filterIsInstance<SessionEntry.ToolResult>()
        assertEquals(listOf("in_progress", "completed", "failed"), calls.map { it.id })
        assertEquals("{\"query\":\"test\"}", calls.first().args)
        assertEquals(listOf("completed", "failed"), results.map { it.id })
        assertEquals("Found it", results.first().output)
        assertFalse(results.first().isError)
        assertTrue(results.last().isError)
        assertEquals("", results.last().output)
    }

    @Test fun linkedNativeToolsKeepTheirOriginalResult() {
        val result = SessionEntry.ToolResult("call", "Read", "contents", isError = false)
        val message = ExecutionMessage("result:4", "tool", "contents", ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), result))
        assertEquals(listOf(result), toolEntries(message))
    }

    @Test fun permissionBeforeAcknowledgementDoesNotLoseExecution() = runTest {
        val registry = ExecutionRegistry()
        registry.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        registry.register(AgentRegistration("a", "Agent", "c", cwd = "/tmp"))
        val accepted = CompletableDeferred<Unit>()
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) = object : ExecutionSession {
                override val capabilities = ExecutionCapabilities(approvals = true)
                override suspend fun prompt(text: String) {
                    state.request(ExecutionRequest("permission", ExecutionRequestKind.Approval, JsonNull))
                    accepted.await()
                    state.accepted()
                }
                override suspend fun cancel() {}
                override suspend fun answer(requestId: String, answer: JsonElement) {}
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope)
        val id = coordinator.create("a").id
        assertEquals(id, coordinator.send(SendParams("a", id, "hello")).sessionId)
        assertFalse(accepted.isCompleted)
        runCurrent()
        advanceTimeBy(65_000)
        runCurrent()
        assertNotEquals(ExecutionPhase.Lost, coordinator.snapshot(id).phase)
        assertEquals("permission", coordinator.snapshot(id).pending.single().id)
        accepted.complete(Unit)
        runCurrent()
        assertNotEquals(ExecutionPhase.Lost, coordinator.snapshot(id).phase)
        coordinator.discard(id)
        coordinator.close()
    }

    @Test fun stopDoesNotWaitForPromptAcknowledgement() = runTest {
        val registry = ExecutionRegistry()
        val config = HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock")))
        registry.save(config)
        registry.register(AgentRegistration("a", "Agent", "c", cwd = "/tmp"))
        val accepted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) = object : ExecutionSession {
                override val capabilities = ExecutionCapabilities(cancel = true)
                override suspend fun prompt(text: String) { events += "prompt"; accepted.await() }
                override suspend fun cancel() { events += "cancel"; state.update(ExecutionPhase.Idle, "cancelled") }
                override suspend fun close() { events += "close" }
                override suspend fun answer(requestId: String, answer: JsonElement) {}
            }
        }
        var readinessPublished = 0
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, readinessChanged = { readinessPublished++ })
        val id = coordinator.create("a").id
        assertEquals(1, readinessPublished)
        coordinator.send(SendParams("a", sessionId = id, text = "hello"))
        runCurrent()
        coordinator.stop(id)
        assertEquals(listOf("prompt", "cancel"), events)
        assertFalse(accepted.isCompleted)
        assertEquals(ExecutionPhase.Idle, coordinator.snapshot(id).phase)
        coordinator.discard(id)
        assertEquals(ExecutionPhase.Lost, coordinator.snapshot(id).phase)
        assertTrue("close" in events)
        assertFalse(coordinator.inUse("c"))
        coordinator.close()
    }
}
