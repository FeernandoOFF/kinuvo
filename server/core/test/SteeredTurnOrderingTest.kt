package kinuvo.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.session.*
import kinuvo.server.execution.NativeExecution
import kinuvo.testing.harness.scriptedModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A message sent with [SendDelivery.Steer] while a turn is still in flight is only read once, at the very start of
 * that turn (`RunSession.load`); one sent after that point cannot be folded into the live request and is instead
 * replayed as a new turn once the current one ends (`QueuedDeliveryTest.steeringThatMissesTheLastRequestRunsAsANewTurn`).
 * This covers that replay end to end through the real built-in harness: the steered message's own entry must still
 * land after the turn it interrupted finished replying, and before that later turn's own reply - never pinned below it.
 */
class SteeredTurnOrderingTest {
    @Test fun steeredMessageThatMissesItsTurnStillPrecedesItsOwnReply() = runBlocking {
        withTimeout(15_000) {
            val fixture = RuntimeFixture()
            val started = Channel<Int>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            var step = 0
            val model = scriptedModel { _, _ ->
                step++
                val mine = step
                flow {
                    started.send(mine)
                    if (mine == 1) release.receive()
                    emit(StreamFrame.TextDelta(if (mine == 1) "reply to first" else "reply to second"))
                    emit(StreamFrame.End())
                }
            }
            val runtime = fixture.runtime(model)
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            val runs = Runs(runtime.log, scope, runtime.native)
            val native = NativeExecution(runtime, runs, scope, { fixture.agent }, { ServerCapabilities("test") }, { _, _ -> }, { _, _ -> })
            val session = runtime.newSession(fixture.agent).id

            native.send(SendParams(fixture.agent.id, session, "first"))
            assertEquals(1, started.receive())
            // Sent while "first" is mid-reply, after its one steering checkpoint already passed: it must wait.
            native.send(SendParams(fixture.agent.id, session, "second", delivery = SendDelivery.Steer))
            release.send(Unit)
            assertEquals(2, started.receive())
            runs.join(session)

            val entries = runtime.log.entries(fixture.agent.id, session)
            val order = entries.mapNotNull {
                when {
                    it is SessionEntry.UserMessage -> it.text
                    it is SessionEntry.AssistantMessage -> it.text
                    else -> null
                }
            }
            assertEquals(listOf("first", "reply to first", "second", "reply to second"), order)
            scope.cancel()
        }
    }
}
