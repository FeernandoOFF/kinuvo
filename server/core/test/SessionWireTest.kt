package kinuvo.server

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.RunOutcome
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.RunId
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.SessionSnapshot
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.session.SessionUpdate
import kinuvo.protocol.session.ToolCallId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a Session subscription delivers for a run: the snapshot's buffered events plus the frames after it are one stream. */
class SessionWireTest {
    private val agent = AgentId("test")
    private val run = RunId("run")
    private val call = ToolCallId("call")

    private class Tape : SessionLog.Subscriber {
        val updates = mutableListOf<SessionUpdate>()
        override fun offer(update: SessionUpdate): Boolean { updates += update; return true }
        val frames get() = updates.map { it.change }
        val runEvents get() = frames.filterIsInstance<SessionChange.Run>().map { it.event }.thread()
        val snapshot: SessionSnapshot get() = frames.filterIsInstance<SessionChange.Snapshot>().single().snapshot
    }

    private fun approval() = PendingInteraction.Approval(InteractionId("a1"), call, "shell", "{}", "ls")

    private suspend fun SessionLog.step(id: SessionId, step: Any) = when (step) {
        is AgentEvent -> publish(agent, id, step)
        is SessionEntry -> { append(agent, id, step); Unit }
        else -> error("step")
    }

    private val reply = listOf<Any>(
        AgentEvent.RunStarted(run),
        AgentEvent.ThinkingContent("r1", "hm"), AgentEvent.ThinkingContent("r1", "mm"),
        SessionEntry.Reasoning("hmmm", messageId = "r1"),
        AgentEvent.TextMessageContent("m1", "he"), AgentEvent.TextMessageContent("m1", "llo"),
        AgentEvent.ToolCallStart(call, "shell", "{}"),
        SessionEntry.ToolCall(call, "shell", "{}"),
        AgentEvent.ToolCallResult(call, "ok", false),
        SessionEntry.ToolResult(call, "shell", "ok"),
        AgentEvent.TextMessageEnd("m1", "hello"),
        SessionEntry.AssistantMessage("hello", "m1"),
        AgentEvent.RunFinished(run),
    )

    @Test fun reconnectingAtAnyPointReplaysTheActiveRunFromItsStart() = runBlocking {
        val full = Tape().also { early ->
            val log = SessionLog(RuntimeFixture().store)
            val id = log.createSession(agent, "").id
            log.subscribe(agent, id, null, 100, early)
            reply.forEach { log.step(id, it) }
        }
        for (cut in 0..reply.size) {
            val log = SessionLog(RuntimeFixture().store)
            val id = log.createSession(agent, "").id
            val early = Tape().also { log.subscribe(agent, id, null, 100, it) }
            reply.take(cut).forEach { log.step(id, it) }
            val seen = early.runEvents.size
            val activeFrom = early.runEvents.indexOfLast { it is AgUiEvent.RunStarted }.takeIf { it >= 0 && early.runEvents.last() !is AgUiEvent.RunFinished } ?: seen
            val late = Tape().also { log.subscribe(agent, id, null, 100, it) }
            val snapshot = late.snapshot
            assertEquals(log.entries(agent, id).size, snapshot.seq, "cut $cut")
            reply.drop(cut).forEach { log.step(id, it) }
            assertEquals(full.runEvents.drop(activeFrom), snapshot.run.thread() + late.runEvents, "cut $cut")
            assertEquals(full.runEvents, early.runEvents, "cut $cut")
        }
    }

    @Test fun aSnapshotAfterTheTerminalEventHasNoRunAndAllEntries() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession(agent, "").id
        reply.forEach { log.step(id, it) }
        val late = Tape().also { log.subscribe(agent, id, null, 100, it) }
        assertTrue(late.snapshot.run.isEmpty())
        assertTrue(late.snapshot.entries.any { it is SessionEntry.AssistantMessage })
        assertEquals(SessionStatus.Idle, log.status(id))
    }

    @Test fun answersOpenANewRunAndTheFirstOneEndsWithTheInterrupt() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession(agent, "").id
        val tape = Tape().also { log.subscribe(agent, id, null, 100, it) }
        listOf(AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "shell", "{}"), AgentEvent.InteractionRequested(approval())).forEach { log.step(id, it) }
        val interrupt = assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(tape.runEvents.last()).outcome)
        assertEquals("tool_call", interrupt.interrupts.single().reason)
        val parked = Tape().also { log.subscribe(agent, id, null, 100, it) }
        assertTrue(parked.snapshot.run.isEmpty())
        assertEquals(listOf(approval()), parked.snapshot.state.pending)
        assertEquals(SessionStatus.Waiting, log.status(id))

        log.step(id, AgentEvent.InteractionResolved(InteractionId("a1"), true))
        log.step(id, AgentEvent.ToolCallResult(call, "ok", false))
        val resumed = Tape().also { log.subscribe(agent, id, null, 100, it) }
        assertEquals(listOf("run", "run~1"), tape.runEvents.filterIsInstance<AgUiEvent.RunStarted>().map { it.runId })
        assertEquals(listOf("run~1"), resumed.snapshot.run.filterIsInstance<AgUiEvent.RunStarted>().map { it.runId })
        log.step(id, AgentEvent.RunFinished(run))
        assertEquals(RunOutcome.Success(), assertIs<AgUiEvent.RunFinished>(tape.runEvents.last()).outcome)
    }

    @Test fun stopOnAnActiveRunEndsItCancelled() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession(agent, "").id
        val tape = Tape().also { log.subscribe(agent, id, null, 100, it) }
        listOf(AgentEvent.RunStarted(run), AgentEvent.TextMessageContent("m1", "part")).forEach { log.step(id, it) }
        log.stopping(id)
        log.step(id, AgentEvent.RunFinished(run, cancelled = true))
        assertEquals(RunOutcome.Cancelled, assertIs<AgUiEvent.RunFinished>(tape.runEvents.last()).outcome)
        assertIs<AgUiEvent.TextMessageEnd>(tape.runEvents[tape.runEvents.size - 2])
        Unit
    }

    @Test fun stopOnAParkedRunResolvesThroughItemsThenIdle() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession(agent, "").id
        val tape = Tape().also { log.subscribe(agent, id, null, 100, it) }
        listOf(AgentEvent.RunStarted(run), AgentEvent.ToolCallStart(call, "shell", "{}"), AgentEvent.InteractionRequested(approval())).forEach { log.step(id, it) }
        val closed = tape.runEvents.size
        tape.updates.clear()
        log.stopping(id)
        log.step(id, AgentEvent.ToolCallResult(call, "Denied (stopped)", true))
        log.step(id, AgentEvent.RunFinished(run, cancelled = true))
        assertTrue(closed > 0)
        assertTrue(tape.runEvents.isEmpty(), "${tape.runEvents}")
        assertEquals(
            listOf(AgentEvent.InteractionResolved(InteractionId("a1")), AgentEvent.RunFinished(run, cancelled = true), AgentEvent.Idle),
            tape.frames.filterIsInstance<SessionChange.Item>().map { it.event },
        )
        assertEquals(SessionStatus.Idle, log.status(id))
        assertTrue(Tape().also { log.subscribe(agent, id, null, 100, it) }.snapshot.state.pending.isEmpty())
    }
}

private fun List<AgUiEvent>.thread() = map {
    when (it) {
        is AgUiEvent.RunStarted -> it.copy(threadId = "thread")
        is AgUiEvent.RunFinished -> it.copy(threadId = "thread")
        else -> it
    }
}
