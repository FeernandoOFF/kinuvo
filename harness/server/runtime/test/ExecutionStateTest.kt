package kinuvo.harness.server

import kinuvo.execution.api.*
import kinuvo.harness.server.execution.*
import kinuvo.protocol.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class ExecutionStateTest {
    @Test fun workingFoldersFollowExecutionHostPathRules() {
        assertTrue(absoluteExecutionPath("/work", Platform.MacOS))
        assertFalse(absoluteExecutionPath("C:/work", Platform.MacOS))
        assertTrue(absoluteExecutionPath("C:/work", Platform.Windows))
        assertTrue(absoluteExecutionPath("C:\\work", Platform.Windows))
        assertTrue(absoluteExecutionPath("\\\\server\\share\\work", Platform.Windows))
        assertFalse(absoluteExecutionPath("C:work", Platform.Windows))
        assertFalse(absoluteExecutionPath("/work", Platform.Windows))
        assertFalse(absoluteExecutionPath("//server", Platform.Windows))
    }

    @Test fun acceptanceIsNotCompletionAndReplayIsKeyed() = runTest {
        val state = ExecutionState("s")
        state.update(ExecutionPhase.Running)
        state.message(ExecutionMessage("m", "assistant", "hello"))
        state.accepted()
        assertEquals(ExecutionPhase.Running, state.snapshot().phase)
        state.message(ExecutionMessage("m", "assistant", "hello world"))
        state.message(ExecutionMessage("m", "assistant", "hello world"))
        assertEquals(listOf(ExecutionMessage("m", "assistant", "hello world")), state.snapshot().messages)
        state.update(ExecutionPhase.Idle, "end_turn")
        assertEquals("end_turn", state.snapshot().stopReason)
    }

    @Test fun reconnectRetainsQuestionsAndOnlyOneClientClaimsAnswer() = runTest {
        val state = ExecutionState("s")
        state.request(ExecutionRequest("q", ExecutionRequestKind.Question, JsonNull))
        state.update(ExecutionPhase.RequiresAction)
        assertEquals("q", state.snapshot().pending.single().id)
        val claims = List(10) { async { state.resolve("q") } }.awaitAll()
        assertEquals(1, claims.count { it })
        state.request(ExecutionRequest("q", ExecutionRequestKind.Question, JsonNull))
        assertTrue(state.snapshot().pending.isEmpty())
    }

    @Test fun brokerSurvivesDeviceReconnectAndResolvesOnce() = runTest {
        val state = ExecutionState("s")
        val broker = ExecutionBroker(state)
        val waiting = async { broker.ask(ExecutionRequest("a", ExecutionRequestKind.Approval, JsonNull)) }
        testScheduler.runCurrent()
        val token = state.snapshot().pending.single().id
        assertNotEquals("a", token)
        assertTrue(broker.answer(token, JsonNull))
        assertFalse(broker.answer(token, JsonNull))
        assertEquals(JsonNull, waiting.await())
        broker.lost()
        assertFailsWith<IllegalStateException> { broker.ask(ExecutionRequest("b", ExecutionRequestKind.Question, JsonNull)) }
    }

    @Test fun lossNeverFinishesSuccessfullyOrAcceptsStaleAnswers() = runTest {
        val state = ExecutionState("s")
        state.update(ExecutionPhase.Running)
        state.request(ExecutionRequest("a", ExecutionRequestKind.Approval, JsonNull))
        state.lost()
        assertEquals(ExecutionPhase.Lost, state.snapshot().phase)
        assertNull(state.snapshot().stopReason)
        assertFalse(state.resolve("a"))
        assertFailsWith<IllegalStateException> { state.update(ExecutionPhase.Idle, "end_turn") }
    }

    @Test fun remoteSelectionIsExplicitAndRegistrationDoesNotImplyOnline() = runTest {
        val registry = ExecutionRegistry()
        val remote = HarnessConnection("r", "Remote", ExecutionConnection.Remote("server"))
        registry.save(remote)
        registry.register(AgentRegistration("local-id", "Agent", "r", remoteAgentId = "agent"))
        assertFailsWith<IllegalStateException> { registry.route("local-id") }
        registry.negotiated("r", ExecutionCapabilities(resume = false))
        assertEquals(remote, registry.route("local-id"))
        assertFailsWith<IllegalArgumentException> { registry.removeConnection("r") }
        registry.offline("r", "Disconnected")
        assertFailsWith<IllegalStateException> { registry.route("local-id") }
        assertFalse(registry.catalog().connections.single().capabilities.resume)
    }
}
