package kinuvo.server

import arrow.core.raise.context.either
import kinuvo.harness.Agent
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.execution.ExecutionRef
import kinuvo.protocol.session.*
import kinuvo.protocol.team.TeamRespondParams
import kinuvo.protocol.team.TeamSendParams
import kinuvo.protocol.team.TeamSpec
import kinuvo.testing.server.FakeHarness
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class InteractionSetsTest {
    private val form = InteractionAnswer.Questions(mapOf("ok" to listOf("yes")))
    private val hold = """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}"""

    private suspend fun untilReady(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    private suspend fun TestServer.extra(agent: Agent, session: SessionId): Broker<Map<String, List<String>>?>.Pending {
        val pending = harness.questions.register()
        harness.log.publish(agent.id, session, AgentEvent.InteractionRequested(PendingInteraction.Questions(pending.id, ToolCallId("extra-${pending.id.value}"), "Extra", emptyList())))
        return pending
    }

    private suspend fun TestServer.live(agent: Agent, session: SessionId): LiveState {
        var state = LiveState()
        val subscriber = SessionLog.Subscriber { update -> (update.change as? SessionChange.Live)?.let { state = it.state }; true }
        harness.log.subscribe(agent.id, session, null, 100, subscriber)
        harness.log.unsubscribe(session, subscriber)
        return state
    }

    private suspend fun TestServer.replies(agent: Agent, session: SessionId) =
        harness.store.entries(agent.id, session).filterIsInstance<SessionEntry.AssistantMessage>().map { it.text }

    @Test fun anAnswerWaitsForEveryOpenInteractionOfTheRun() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = { on("hold") { toolCall("ask_user", hold); reply("parent done") } }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id, "hold")
            untilReady { server.harness.log.pending(session.id).isNotEmpty() }
            val first = server.extra(agent, session.id)
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val ask = server.harness.log.pending(session.id).first { it.id != first.id }
            suspend fun respond(id: InteractionId) = client.call(Api.Respond, RespondParams(session.id, id, form))

            assertTrue(respond(ask.id))
            delay(300)
            assertEquals(2, server.harness.log.pending(session.id).size)
            assertEquals(setOf(ask.id), server.live(agent, session.id).staged)
            assertFalse(server.replies(agent, session.id).contains("parent done"))
            assertFalse(respond(ask.id), "a duplicate answer is refused")

            val late = server.extra(agent, session.id)
            assertTrue(respond(first.id))
            delay(300)
            assertFalse(server.replies(agent, session.id).contains("parent done"), "a sibling raised before release joins the set")
            assertTrue(respond(late.id))
            untilReady { server.replies(agent, session.id).contains("parent done") }
            assertEquals(mapOf("ok" to listOf("yes")), first.await())
            assertEquals(mapOf("ok" to listOf("yes")), late.await())
            assertFalse(respond(first.id), "a late answer is refused")
        }
    }

    @Test fun anAnswerStagedForAnInteractionKeepsItsTimeoutFromSettlingIt() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = { on("hold") { toolCall("ask_user", hold); reply("parent done") } }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id, "hold")
            untilReady { server.harness.log.pending(session.id).isNotEmpty() }
            val ask = server.harness.log.pending(session.id).single()
            val extra = server.extra(agent, session.id)
            val waiting = async(Dispatchers.Default) { extra.await(50.milliseconds) }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            assertTrue(client.call(Api.Respond, RespondParams(session.id, extra.id, form)))
            delay(300)
            assertFalse(waiting.isCompleted, "the staged answer outlives the timeout")
            assertTrue(client.call(Api.Respond, RespondParams(session.id, ask.id, form)))
            assertEquals(mapOf("ok" to listOf("yes")), waiting.await())
        }
    }

    @Test fun aTimeoutRacingAnAnswerSettlesExactlyOnce() = runBlocking(Dispatchers.Default) {
        repeat(300) {
            val broker = Broker<ApprovalDecision>()
            val pending = broker.register()
            val waiting = async { pending.await(1.milliseconds) }
            val answered = async { broker.respond(pending.id, ApprovalDecision.AllowOnce) }
            assertEquals(answered.await(), waiting.await() != null)
            assertFalse(broker.respond(pending.id, ApprovalDecision.AllowOnce))
        }
    }

    @Test fun aQueuedMessageWaitsForTheOpenInteraction() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = { on("hold") { toolCall("ask_user", hold); reply("parent done") } }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val session = server.startSession(agent.id, "hold")
            untilReady { server.harness.log.pending(session.id).isNotEmpty() }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            client.call(Api.Send, SendParams(agent.id, session.id, "second"))
            delay(300)
            assertEquals(listOf("second"), server.live(agent, session.id).queued.map { it.text })
            val ask = server.harness.log.pending(session.id).single()
            assertTrue(client.call(Api.Respond, RespondParams(session.id, ask.id, form)))
            untilReady { server.harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.UserMessage>().size == 2 }
            assertEquals(listOf("hold", "second"), server.harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.UserMessage>().map { it.text })
        }
    }

    @Test fun aSubAgentWakeWaitsForTheOpenInteractionOfAnIdleSession() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = { on("regular") { reply("regular answer") } }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val open = server.extra(agent, parent.id)
            val children = server.harness.subAgents!!
            either { children.spawn(agent.id, parent.id, ToolCallId("regular"), agent.id, "regular") }.getOrNull() ?: error("spawn failed")
            untilReady { children.list(agent.id, parent.id).values.all { it.status == SubAgentStatus.Completed } }
            delay(300)
            fun reports() = runBlocking { server.harness.store.entries(agent.id, parent.id).filterIsInstance<SessionEntry.ToolReport>() }
            assertTrue(reports().isEmpty())
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, open.id, form)))
            open.await()
            open.close()
            server.harness.log.publish(agent.id, parent.id, AgentEvent.InteractionResolved(open.id))
            untilReady { reports().isNotEmpty() }
        }
    }

    @Test fun teamsAndSessionsAnswerThroughTheSameSet() = runBlocking {
        lateinit var first: Agent
        TestServer.start(script = { on("hold") { toolCall("ask_user", hold); reply("first done") } }, scenario = {
            first = agent("first") { it.copy(approvals = "full") }; agent("second")
        }).use { server ->
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(AgentId("first"), AgentId("second")))).team
            client.call(Api.TeamSend, TeamSendParams(team.id, "hold"))
            val member = team.members.first().sessionId
            untilReady { server.harness.log.pending(member).isNotEmpty() }
            val ask = server.harness.log.pending(member).single()
            val extra = server.extra(first, member)
            assertTrue(client.call(Api.TeamRespond, TeamRespondParams(team.id, ask.id, form)))
            delay(300)
            assertFalse(server.replies(first, member).contains("first done"))
            assertFalse(client.call(Api.TeamRespond, TeamRespondParams(team.id, ask.id, form)))
            assertTrue(client.call(Api.Respond, RespondParams(member, extra.id, form)))
            untilReady { server.replies(first, member).contains("first done") }
        }
    }

    private fun parallelRequests() = FakeHarness("fake") { _ ->
        coroutineScope {
            launch { askQuestions("A", Question("q", "A")) }
            launch { askQuestions("B", Question("q", "B")) }
        }
        reply("both answered")
    }

    @Test fun parallelExternalRequestsReleaseTogetherAndStopCancelsThem() = runBlocking {
        val harness = parallelRequests()
        TestServer.start(harnesses = listOf(harness), scenario = { externalAgent(harness, "ext", "External") }).use { server ->
            val session = server.startSession(AgentId("ext"), "Start")
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            suspend fun snapshot() = client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value))
            untilReady { snapshot().pending.size == 2 }
            val (a, b) = snapshot().pending.map { it.id }
            val answer = InteractionAnswer.Questions(emptyMap())
            assertTrue(client.call(Api.Respond, RespondParams(session.id, a, answer)))
            delay(300)
            assertEquals(2, snapshot().pending.size)
            assertFalse(client.call(Api.Respond, RespondParams(session.id, a, answer)), "duplicate")
            assertTrue(client.call(Api.Respond, RespondParams(session.id, b, answer)))
            untilReady { snapshot().phase == ExecutionPhase.Idle }
            assertTrue(snapshot().pending.isEmpty())

            client.call(Api.Send, SendParams(AgentId("ext"), session.id, "again"))
            untilReady { snapshot().pending.size == 2 }
            val (c, d) = snapshot().pending.map { it.id }
            assertTrue(client.call(Api.Respond, RespondParams(session.id, c, answer)))
            client.call(Api.Stop, StopParams(session.id))
            untilReady { snapshot().pending.isEmpty() }
            assertFalse(client.call(Api.Respond, RespondParams(session.id, d, answer)), "after stop d")
            assertFalse(client.call(Api.Respond, RespondParams(session.id, c, answer)), "after stop c")
        }
    }

    @Test fun aLostExternalRequestCannotBeAnswered() = runBlocking {
        val harness = parallelRequests()
        TestServer.start(harnesses = listOf(harness), scenario = { externalAgent(harness, "ext", "External") }).use { server ->
            val session = server.startSession(AgentId("ext"), "Start")
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            suspend fun snapshot() = client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value))
            untilReady { snapshot().pending.size == 2 }
            val ids = snapshot().pending.map { it.id }
            client.call(Api.CloseExecution, ExecutionRef(session.id.value))
            assertEquals(ExecutionPhase.Lost, snapshot().phase)
            assertTrue(snapshot().pending.isEmpty())
            ids.forEach { assertFalse(client.call(Api.Respond, RespondParams(session.id, it, InteractionAnswer.Questions(emptyMap())))) }
        }
    }
}
