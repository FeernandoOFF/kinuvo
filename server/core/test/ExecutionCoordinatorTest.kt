package kinuvo.server

import arrow.core.raise.context.either
import kinuvo.harness.*
import kinuvo.server.store.InMemoryStore
import kinuvo.server.execution.*
import kinuvo.protocol.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.tool.ToolNames
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionCoordinatorTest {
    @Test fun newSessionUsesLastNegotiatedAttachmentTypes() = runTest {
        val registry = registry()
        val coordinator = ExecutionCoordinator(registry, null, backgroundScope, SessionLog(InMemoryStore()))
        val agent = AgentId("a")
        val readiness = ExecutionReadiness(true, true)
        val server = ServerCapabilities("server")
        assertEquals(emptyMap(), coordinator.capabilities(agent, null, readiness, server, 0).attachments)
        registry.negotiated("c", ExecutionCapabilities(supportedMimeTypes = setOf("image/png", "text/plain")))
        assertEquals(
            mapOf("image/png" to CapabilityState(), "text/plain" to CapabilityState()),
            coordinator.capabilities(agent, null, readiness, server, 0).attachments,
        )
    }

    @Test fun failedChildOpenRollsBackSessionAndRecovery() = runTest {
        val store = InMemoryStore()
        val log = SessionLog(store)
        val recovery = MemoryExecutionRecovery()
        val agent = AgentId("a")
        val parent = log.createSession(AgentId("parent"), "")
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                assertEquals(state.sessionId, recovery.load().single().sessionId)
                state.reference("allocated")
                error("Open failed")
            }
        }
        val coordinator = ExecutionCoordinator(registry(), factory, backgroundScope, log, recovery)
        log.onDelete = { _, id -> assertFalse(coordinator.owns(id)) }
        val error = assertFailsWith<IllegalStateException> {
            coordinator.create(agent, SessionParent(parent.agentId, parent.id, ToolCallId("spawn"), 1))
        }
        assertEquals("Open failed", error.message)
        assertTrue(store.sessions(agent).isEmpty())
        assertTrue(recovery.load().isEmpty())
        assertEquals(parent.id, log.session(parent.agentId, parent.id).id)
    }

    @Test fun durableOutputPrecedesTerminalAndSubmissionRetryDoesNotResend() = runTest {
        val registry = registry()
        val log = SessionLog(InMemoryStore())
        val recovery = MemoryExecutionRecovery()
        var prompts = 0
        lateinit var state: ExecutionState
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, execution: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                state = execution
                state.reference("source")
                return object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities(cancel = true)
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance { prompts++; return ExecutionAcceptance("accepted") }
                    override suspend fun cancel() {}
                    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, log, recovery)
        val id = coordinator.create(AgentId("a")).id
        assertFailsWith<IllegalArgumentException> {
            coordinator.send(SendParams(AgentId("a"), id, "steer", delivery = SendDelivery.Steer))
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.send(SendParams(AgentId("a"), id, "queue", delivery = SendDelivery.Queue))
        }
        coordinator.send(SendParams(AgentId("a"), id, "hello", submissionId = "submission"))
        runCurrent()
        coordinator.send(SendParams(AgentId("a"), id, "hello", submissionId = "submission"))
        assertEquals(1, prompts)
        assertEquals("accepted", recovery.load().single().submissions["submission"])
        val sink = state.boundSink()
        sink.publish(AgentEvent.ExecutionEntryChanged(SessionEntry.AssistantMessage("reply", "reply")))
        sink.commit(listOf(SessionEntry.AssistantMessage("reply", "reply")))
        sink.publish(AgentEvent.RunFinished(RunId("run")))
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
        assertEquals("reply", log.entries(AgentId("a"), id).filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        assertTrue(recovery.load().single().checkpoint.isEmpty())
        coordinator.closeExecution(id)
        assertFailsWith<IllegalStateException> { sink.commit(listOf(SessionEntry.AssistantMessage("late", "late"))) }
        assertTrue(recovery.load().single().closed)
    }

    @Test fun recoveryReplaysCheckpointOnceAndInterruptedDraftStaysNested() = runTest {
        val log = SessionLog(InMemoryStore())
        val complete = log.createSession(AgentId("a"), "").id
        val incomplete = log.createSession(AgentId("a"), "").id
        val registry = registry()
        val storage = MemoryExecutionRecovery()
        val config = registry.configured(AgentId("a"))
        val registration = requireNotNull(registry.registration(AgentId("a")))
        val reply = SessionEntry.AssistantMessage("complete", "m")
        storage.save(ExecutionRecovery(AgentId("a"), complete, config, registration, "one", checkpoint = listOf(reply)))
        storage.save(ExecutionRecovery(AgentId("a"), incomplete, config, registration, "two", draft = listOf(SessionEntry.AssistantMessage("partial", "draft"))))
        val coordinator = ExecutionCoordinator(registry, null, backgroundScope, log, storage)
        repeat(2) { coordinator.recover() }
        assertEquals(listOf(reply), log.entries(AgentId("a"), complete).filterIsInstance<SessionEntry.AssistantMessage>().map { it.copy(writtenAt = null) })
        assertTrue(log.entries(AgentId("a"), incomplete).filterIsInstance<SessionEntry.AssistantMessage>().isEmpty())
        assertEquals("partial", (log.entries(AgentId("a"), incomplete).filterIsInstance<SessionEntry.Notice>().single().draft.single() as SessionEntry.AssistantMessage).text)
        assertEquals(ExecutionPhase.Lost, coordinator.snapshot(complete).phase)
    }

    @Test fun lateOldPromptFailureCannotRevokeReplacementAndStopDoesNotWaitForAcceptance() = runTest {
        val registry = registry()
        val states = mutableListOf<ExecutionState>()
        val oldFailure = CompletableDeferred<Unit>()
        var cancellations = 0
        var promptStarted = false
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                states += state
                state.reference("source")
                return object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities(resume = true, cancel = true)
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance = withContext(NonCancellable) {
                        promptStarted = true
                        oldFailure.await(); error("Late old prompt failure")
                    }
                    override suspend fun cancel() { assertTrue(promptStarted); cancellations++ }
                    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, SessionLog(InMemoryStore()))
        val id = coordinator.create(AgentId("a")).id
        coordinator.send(SendParams(AgentId("a"), id, "hello", submissionId = "one"))
        coordinator.stop(id)
        assertEquals(1, cancellations)
        assertFalse(oldFailure.isCompleted)
        states.first().lost()
        runCurrent()
        assertNull(either { coordinator.resume(id) }.leftOrNull())
        oldFailure.complete(Unit)
        runCurrent()
        assertEquals(ExecutionPhase.Idle, coordinator.snapshot(id).phase)
        assertTrue(coordinator.agentInUse(AgentId("a")))
        coordinator.close()
    }

    @Test fun uncertainWakeSubmissionIsKeptForRecoveryWithoutRetrying() = runTest {
        val agent = AgentId("a")
        val log = SessionLog(InMemoryStore())
        val failPrompt = CompletableDeferred<Unit>()
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
                object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities()
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                        failPrompt.await()
                        error("Connection ended without a prompt acknowledgement")
                    }
                    override suspend fun cancel() {}
                    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                }
        }
        val recovery = MemoryExecutionRecovery()
        val coordinator = ExecutionCoordinator(registry(), factory, backgroundScope, log, recovery)
        val id = coordinator.create(agent).id
        var restored = false
        val report = SessionEntry.ToolReport(ToolNames.AGENTS_SPAWN, null, "completion")
        coordinator.send(SendParams(agent, id, report.content, submissionId = "wake"), report, onRejected = { restored = true })
        failPrompt.complete(Unit)
        runCurrent()
        assertEquals(ExecutionPhase.Lost, coordinator.snapshot(id).phase)
        assertFalse(restored)
        assertEquals(setOf("wake"), recovery.load().single().submissions.keys)
        assertTrue(recovery.load().single().acceptedSubmissions.isEmpty())
    }

    @Test fun stopCancelsUntrackedHarnessWorkAndDeleteSettlesAnUnconfirmedTurn() = runTest {
        val agent = AgentId("a")
        val log = SessionLog(InMemoryStore())
        lateinit var sink: ExecutionSink
        var cancellations = 0
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                sink = state.boundSink()
                return object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities(cancel = true)
                    override suspend fun prompt(input: ExecutionInput) = ExecutionAcceptance()
                    override suspend fun cancel() { cancellations++ }
                    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry(), factory, backgroundScope, log)
        val finished = mutableListOf<Pair<Boolean, String?>>()
        coordinator.onFinish = { _, _, cancelled, error -> finished += cancelled to error }
        val id = coordinator.create(agent).id
        coordinator.stop(id)
        assertEquals(1, cancellations)

        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
        coordinator.stop(id)
        assertEquals(2, cancellations)
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
        withTimeout(1_000) { coordinator.join(id) }
        assertEquals(true to null, finished.single())

        coordinator.send(SendParams(agent, id, "unconfirmed"))
        coordinator.stop(id)
        withTimeout(1_000) { coordinator.delete(agent, id) }
        assertFalse(coordinator.owns(id))
        assertEquals(true to "Harness connection lost", finished.last())
        assertFailsWith<IllegalStateException> { sink.commit(listOf(SessionEntry.AssistantMessage("late"))) }
    }

    @Test fun linkedNativeToolsKeepTheirOriginalResult() {
        val result = SessionEntry.ToolResult(ToolCallId("call"), "Read", "contents", isError = false)
        val message = ExecutionMessage("result:4", "tool", "contents", ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), result))
        assertEquals(listOf(result), toolEntries(message))
    }

    private suspend fun registry() = ExecutionRegistry().also {
        it.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        it.register(AgentRegistration(AgentId("a"), "Agent", "c", cwd = "/tmp"))
    }
}
