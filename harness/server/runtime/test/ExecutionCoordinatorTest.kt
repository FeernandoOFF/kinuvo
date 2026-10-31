package kinuvo.harness.server

import kinuvo.execution.api.*
import kinuvo.harness.core.store.InMemoryStore
import kinuvo.harness.server.execution.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionCoordinatorTest {
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
                    override suspend fun answer(requestId: String, answer: JsonElement) {}
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, log, recovery)
        val id = coordinator.create("a").id
        coordinator.send(SendParams("a", id, "hello", submissionId = "submission"))
        runCurrent()
        coordinator.send(SendParams("a", id, "hello", submissionId = "submission"))
        assertEquals(1, prompts)
        assertEquals("accepted", recovery.load().single().submissions["submission"])
        val sink = state.boundSink()
        sink.publish(AgentEvent.ExecutionEntryChanged(SessionEntry.AssistantMessage("reply", "reply")))
        sink.commit(listOf(SessionEntry.AssistantMessage("reply", "reply")))
        sink.publish(AgentEvent.RunFinished("run"))
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
        assertEquals("reply", log.entries("a", id).filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        assertTrue(recovery.load().single().checkpoint.isEmpty())
        coordinator.closeExecution(id)
        assertFailsWith<IllegalStateException> { sink.commit(listOf(SessionEntry.AssistantMessage("late", "late"))) }
        assertTrue(recovery.load().single().closed)
    }

    @Test fun recoveryReplaysCheckpointOnceAndInterruptedDraftStaysNested() = runTest {
        val log = SessionLog(InMemoryStore())
        val complete = log.createSession("a", "").id
        val incomplete = log.createSession("a", "").id
        val registry = registry()
        val storage = MemoryExecutionRecovery()
        val config = registry.configured("a")
        val registration = requireNotNull(registry.registration("a"))
        val reply = SessionEntry.AssistantMessage("complete", "m")
        storage.save(ExecutionRecovery("a", complete, config, registration, "one", checkpoint = listOf(reply)))
        storage.save(ExecutionRecovery("a", incomplete, config, registration, "two", draft = listOf(SessionEntry.AssistantMessage("partial", "draft"))))
        val coordinator = ExecutionCoordinator(registry, null, backgroundScope, log, storage)
        repeat(2) { coordinator.recover() }
        assertEquals(listOf(reply), log.entries("a", complete).filterIsInstance<SessionEntry.AssistantMessage>())
        assertTrue(log.entries("a", incomplete).filterIsInstance<SessionEntry.AssistantMessage>().isEmpty())
        assertEquals("partial", (log.entries("a", incomplete).filterIsInstance<SessionEntry.Notice>().single().draft.single() as SessionEntry.AssistantMessage).text)
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
                    override suspend fun answer(requestId: String, answer: JsonElement) {}
                }
            }
        }
        val coordinator = ExecutionCoordinator(registry, factory, backgroundScope, SessionLog(InMemoryStore()))
        val id = coordinator.create("a").id
        coordinator.send(SendParams("a", id, "hello", submissionId = "one"))
        coordinator.stop(id)
        assertEquals(1, cancellations)
        assertFalse(oldFailure.isCompleted)
        states.first().lost()
        runCurrent()
        coordinator.resume(id)
        oldFailure.complete(Unit)
        runCurrent()
        assertEquals(ExecutionPhase.Idle, coordinator.snapshot(id).phase)
        assertTrue(coordinator.agentInUse("a"))
        coordinator.close()
    }

    @Test fun linkedNativeToolsKeepTheirOriginalResult() {
        val result = SessionEntry.ToolResult("call", "Read", "contents", isError = false)
        val message = ExecutionMessage("result:4", "tool", "contents", ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), result))
        assertEquals(listOf(result), toolEntries(message))
    }

    private suspend fun registry() = ExecutionRegistry().also {
        it.save(HarnessConnection("c", "ACP", ExecutionConnection.Acp(listOf("mock"))))
        it.register(AgentRegistration("a", "Agent", "c", cwd = "/tmp"))
    }
}
