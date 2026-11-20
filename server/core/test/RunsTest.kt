package kinuvo.server

import kinuvo.harness.*
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RunsTest {
    @Test fun joinsWaitingOnFactoryOpenResumeAfterReplacementOrFailure() = runTest {
        val fixture = RuntimeFixture()
        val session = fixture.store.createSession(fixture.agent.id, "")
        for (failOpen in listOf(false, true)) {
            val opening = CompletableDeferred<Unit>()
            val prompt = CompletableDeferred<Unit>()
            val factory = object : ExecutionFactory {
                override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                    opening.await()
                    check(!failOpen) { "Factory open failed" }
                    return object : ExecutionSession {
                        override val capabilities = ExecutionCapabilities()
                        override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance { prompt.await(); return ExecutionAcceptance() }
                        override suspend fun cancel() {}
                        override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                    }
                }
            }
            val runs = Runs(SessionLog(fixture.store), this, factory)
            val start = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { runs.start(fixture.agent, session.id, ExecutionInput("start")) }
            }
            val join = async(start = CoroutineStart.UNDISPATCHED) { runs.join(session.id) }
            assertFalse(join.isCompleted)
            opening.complete(Unit)
            val result = start.await()
            assertEquals(failOpen, result.isFailure)
            withTimeout(1000) { join.await() }
            prompt.complete(Unit)
            result.getOrNull()?.job?.join()
            assertFalse(runs.isActive(session.id))
        }
    }

    @Test fun cancellationBeforeFirstDispatchFinishesAndClosesTheRun() = runTest {
        val fixture = RuntimeFixture()
        val session = fixture.store.createSession(fixture.agent.id, "")
        var prompts = 0
        var closed = 0
        val finished = mutableListOf<Boolean>()
        val done = mutableListOf<Boolean>()
        val cancelled = mutableListOf<SessionId>()
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) = object : ExecutionSession {
                override val capabilities = ExecutionCapabilities()
                override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance { prompts++; awaitCancellation() }
                override suspend fun close() { closed++ }
                override suspend fun cancel() {}
                override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
            }
        }
        val runs = Runs(SessionLog(fixture.store), this, factory)
        runs.onCancel = { cancelled += it }
        val order = mutableListOf<String>()
        runs.onFinish { _, _, stopped -> finished += stopped; order += "first" }
        runs.onFinish { _, _, _ -> order += "second" }
        val run = runs.start(fixture.agent, session.id, ExecutionInput("start"), onDone = { done += it })
        runs.stop(session.id)
        run.job.join()
        assertEquals(listOf(true), finished)
        assertEquals(listOf("first", "second"), order)
        assertEquals(listOf(true), done)
        assertEquals(listOf(session.id), cancelled)
        assertEquals(1, closed)
        assertEquals(0, prompts)
        assertTrue(runs.sessions().isEmpty())
        assertEquals(0, runs.activity.value.runs)
    }
}
