package kinuvo.server

import kinuvo.harness.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.server.execution.NativeExecution
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

class QueuedDeliveryTest {
    @Test fun queueIsFifoAndCancelRemovesOnlyWaitingMessages() = exercise { native, started, release, _, session ->
        native.send(SendParams(agent, session, "first"))
        assertEquals("first", started.receive())
        val second = native.send(SendParams(agent, session, "second", delivery = SendDelivery.Queue))
        native.send(SendParams(agent, session, "third", delivery = SendDelivery.Queue))
        native.send(SendParams(agent, session, "fourth", delivery = SendDelivery.Queue))
        native.cancelQueued(CancelQueuedParams(agent, session, second.runId.value))
        release.send(Unit)
        assertEquals("third", started.receive())
        release.send(Unit)
        assertEquals("fourth", started.receive())
        release.send(Unit)
    }

    @Test fun stopKeepsQueueUntilAnExplicitTurnAndSteerAfterEndStartsATurn() = exercise { native, started, release, runs, session ->
        native.send(SendParams(agent, session, "first"))
        started.receive()
        native.send(SendParams(agent, session, "waiting", delivery = SendDelivery.Queue))
        native.stop(session)
        runs.join(session)
        yield()
        assertTrue(started.tryReceive().isFailure)
        native.send(SendParams(agent, session, "resume", delivery = SendDelivery.Steer))
        assertEquals("resume", started.receive())
        release.send(Unit)
        assertEquals("waiting", started.receive())
        release.send(Unit)
    }

    @Test fun steerIsTakenAfterInFlightWorkWithoutStartingAnotherRun() = exercise { native, started, release, runs, session ->
        native.send(SendParams(agent, session, "first"))
        started.receive()
        native.send(SendParams(agent, session, "adjust", delivery = SendDelivery.Steer))
        assertTrue(started.tryReceive().isFailure)
        release.send(Unit)
        runs.join(session)
    }

    @Test fun steeringThatMissesTheLastRequestRunsAsANewTurn() = exercise { native, started, release, _, session ->
        native.send(SendParams(agent, session, "no-safe-point"))
        started.receive()
        native.send(SendParams(agent, session, "adjust", delivery = SendDelivery.Steer))
        release.send(Unit)
        assertEquals("adjust", started.receive())
        release.send(Unit)
    }

    @Test fun failureStillAdvancesTheQueue() = exercise { native, started, release, _, session ->
        native.send(SendParams(agent, session, "fail"))
        started.receive()
        native.send(SendParams(agent, session, "next", delivery = SendDelivery.Queue))
        release.send(Unit)
        assertEquals("next", started.receive())
        release.send(Unit)
    }

    private val agent = AgentId("test")

    private fun exercise(test: suspend (NativeExecution, Channel<String>, Channel<Unit>, Runs, SessionId) -> Unit): Unit = runBlocking {
        withTimeout(15_000) {
            val fixture = RuntimeFixture()
            val runtime = fixture.runtime(kinuvo.testing.harness.scriptedModel { _, _ -> kotlinx.coroutines.flow.emptyFlow() })
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            val started = Channel<String>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            val factory = object : ExecutionFactory {
                override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) = object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities()
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                        val sink = state.boundSink()
                        sink.publish(AgentEvent.RunStarted(requireNotNull(input.runId)))
                        sink.commit(listOf(SessionEntry.UserMessage(input.text)))
                        started.send(input.text)
                        release.receive()
                        val steering = if (input.text == "no-safe-point") emptyList() else input.steering()
                        sink.commit(steering)
                        if (input.text == "first" && steering.isNotEmpty()) assertEquals(listOf("adjust"), steering.map { assertIs<SessionEntry.UserMessage>(it).text })
                        sink.publish(if (input.text == "fail") AgentEvent.RunError(requireNotNull(input.runId), "failed") else AgentEvent.RunFinished(requireNotNull(input.runId)))
                        return ExecutionAcceptance()
                    }
                    override suspend fun cancel() {}
                    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                }
            }
            val runs = Runs(runtime.log, scope, factory)
            val session = runtime.newSession(fixture.agent).id
            val native = NativeExecution(runtime, runs, scope, { fixture.agent }, { ServerCapabilities("test") }, { _, _ -> }, { _, _ -> })
            try { test(native, started, release, runs, session); runs.join(session) }
            finally { scope.cancel(); scope.coroutineContext.job.join(); runtime.close() }
        }
    }
}
