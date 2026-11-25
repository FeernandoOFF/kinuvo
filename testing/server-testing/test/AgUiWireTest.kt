package kinuvo.testing.server

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.RunOutcome
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.approval.approval
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionParams
import kinuvo.protocol.session.SessionSnapshot
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.session.StopParams
import kinuvo.protocol.session.SubscribeParams
import kinuvo.testing.harness.awaitCondition
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The Session subscription of a real Server run: AG-UI 1.0 events inside a Kinuvo envelope. */
class AgUiWireTest {
    private val deploy = FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" }
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(requireNotNull(javaClass.getResourceAsStream("/agui/schema.json")).readBytes().decodeToString())

    private fun conform(events: List<AgUiEvent>) {
        var running = false
        val open = mutableSetOf<String>()
        events.forEach { event ->
            val json = ProtocolJson.encodeToString(AgUiEvent.serializer(), event)
            val errors = schema.validate(json, InputFormat.JSON)
            assertTrue(errors.isEmpty(), "$json\n${errors.joinToString("\n")}")
            when (event) {
                is AgUiEvent.RunStarted -> { assertTrue(!running, "RUN_STARTED inside a run"); running = true }
                is AgUiEvent.RunFinished, is AgUiEvent.RunError -> { assertTrue(running, "terminal outside a run"); assertTrue(open.isEmpty(), "open items $open"); running = false }
                is AgUiEvent.TextMessageStart -> { assertTrue(running); open += "text:${event.messageId}" }
                is AgUiEvent.TextMessageEnd -> assertTrue(open.remove("text:${event.messageId}"))
                is AgUiEvent.ReasoningMessageStart -> { assertTrue(running); open += "reasoning:${event.messageId}" }
                is AgUiEvent.ReasoningMessageEnd -> assertTrue(open.remove("reasoning:${event.messageId}"))
                is AgUiEvent.ToolCallStart -> { assertTrue(running); open += "tool:${event.toolCallId}" }
                is AgUiEvent.ToolCallEnd -> assertTrue(open.remove("tool:${event.toolCallId}"))
                else -> assertTrue(running, "$event outside a run")
            }
        }
        assertTrue(!running, "the stream ended inside a run")
    }

    private suspend fun runUntilApproval(client: KinuvoClient, session: SessionInfo) = coroutineScope {
        val approval = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Item)?.event?.approval }.first() }
        client.call(Api.Send, SendParams(AgentId("work"), session.id, "please deploy"))
        approval.await()
    }

    @Test
    fun aRealRunStreamsAnInterruptThenANewRunAndEveryEventConforms() = runBlocking {
        lateinit var session: SessionInfo
        val server = TestServer.start(
            plugins = listOf(FakeTools(deploy)),
            script = { on("please deploy") { toolCall("deploy"); reply("Done") } },
            scenario = { session = session(agent("work")) },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val changes = mutableListOf<SessionChange>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).filter { it.sessionId == session.id }.collect { synchronized(changes) { changes += it.change } } }
                client.call(Api.Subscribe, SubscribeParams(AgentId("work"), session.id))
                val approval = runUntilApproval(client, session)
                awaitCondition("the run to park", 5.seconds) { synchronized(changes) { changes.any { (it as? SessionChange.Run)?.event is AgUiEvent.RunFinished } } }

                val reconnected = server.client("phone", server.pairDevice("phone")).also { it.connect() }
                val snapshot = snapshotOf(reconnected, session)
                assertTrue(snapshot.run.isEmpty())
                assertEquals(listOf(approval.id), snapshot.state.pending.map { it.id })

                assertTrue(client.call(Api.Respond, RespondParams(session.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                awaitCondition("the run to end", 5.seconds) { client.call(Api.Session, SessionParams(AgentId("work"), session.id)).status == SessionStatus.Idle }
                collector.cancel()
                val run = synchronized(changes) { changes.filterIsInstance<SessionChange.Run>().map { it.event } }
                conform(run)
                val started = run.filterIsInstance<AgUiEvent.RunStarted>().map { it.runId }
                assertEquals(listOf(started[0], "${started[0]}~1"), started)
                val interrupted = assertIs<RunOutcome.Interrupted>(run.filterIsInstance<AgUiEvent.RunFinished>().first().outcome)
                assertEquals(approval.id.value, interrupted.interrupts.single().id)
                assertEquals(approval.toolCallId.value, interrupted.interrupts.single().toolCallId)
                assertEquals(RunOutcome.Success(), run.filterIsInstance<AgUiEvent.RunFinished>().last().outcome)
                assertTrue(run.indexOfFirst { it is AgUiEvent.ToolCallResult } > run.indexOfLast { it is AgUiEvent.RunStarted })
                assertTrue(run.filterIsInstance<AgUiEvent.TextMessageContent>().any { it.delta.contains("Done") })
                reconnected.close()
            }
        }
    }

    @Test
    fun anApprovalInsideAScriptInterruptsTheRun() = runBlocking {
        lateinit var session: SessionInfo
        val server = TestServer.start(
            plugins = listOf(FakeTools(deploy, FakeScript("script", "deploy"))),
            script = { on("please script") { toolCall("script"); reply("Done") } },
            scenario = { session = session(agent("work")) },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val changes = mutableListOf<SessionChange>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).filter { it.sessionId == session.id }.collect { synchronized(changes) { changes += it.change } } }
                client.call(Api.Subscribe, SubscribeParams(AgentId("work"), session.id))
                val approval = coroutineScope {
                    val asked = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Item)?.event?.approval }.first() }
                    client.call(Api.Send, SendParams(AgentId("work"), session.id, "please script"))
                    asked.await()
                }
                awaitCondition("the run to park", 5.seconds) { synchronized(changes) { changes.any { (it as? SessionChange.Run)?.event is AgUiEvent.RunFinished } } }
                assertTrue(client.call(Api.Respond, RespondParams(session.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                awaitCondition("the run to end", 5.seconds) { client.call(Api.Session, SessionParams(AgentId("work"), session.id)).status == SessionStatus.Idle }
                collector.cancel()
                val run = synchronized(changes) { changes.filterIsInstance<SessionChange.Run>().map { it.event } }
                conform(run)
                val interrupted = assertIs<RunOutcome.Interrupted>(run.filterIsInstance<AgUiEvent.RunFinished>().first().outcome)
                assertEquals(approval.id.value, interrupted.interrupts.single().id)
            }
        }
    }

    @Test
    fun stopOnAParkedRunEndsWithItemsAndNoNewRun() = runBlocking {
        lateinit var session: SessionInfo
        val server = TestServer.start(
            plugins = listOf(FakeTools(deploy)),
            script = { on("please deploy") { toolCall("deploy"); reply("Done") } },
            scenario = { session = session(agent("work")) },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val changes = mutableListOf<SessionChange>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).filter { it.sessionId == session.id }.collect { synchronized(changes) { changes += it.change } } }
                client.call(Api.Subscribe, SubscribeParams(AgentId("work"), session.id))
                val approval = runUntilApproval(client, session)
                awaitCondition("the run to park", 5.seconds) { synchronized(changes) { changes.any { (it as? SessionChange.Run)?.event is AgUiEvent.RunFinished } } }
                val parkedAt = synchronized(changes) { changes.size }
                client.call(Api.Stop, StopParams(session.id))
                awaitCondition("Idle", 5.seconds) { synchronized(changes) { changes.drop(parkedAt).any { (it as? SessionChange.Item)?.event == AgentEvent.Idle } } }
                collector.cancel()
                val after = synchronized(changes) { changes.drop(parkedAt) }
                assertTrue(after.none { it is SessionChange.Run }, "$after")
                assertTrue(after.any { ((it as? SessionChange.Item)?.event as? AgentEvent.InteractionResolved)?.id == approval.id }, "$after")
                assertEquals(SessionStatus.Idle, client.call(Api.Session, SessionParams(AgentId("work"), session.id)).status)
                conform(synchronized(changes) { changes.filterIsInstance<SessionChange.Run>().map { it.event } })
            }
        }
    }

    @Test
    fun stopOnAnActiveRunEndsItCancelled() = runBlocking {
        lateinit var session: SessionInfo
        val never = CompletableDeferred<String>()
        val server = TestServer.start(
            plugins = listOf(FakeTools(FakeTool("wait") { never.await() })),
            script = { on("hold") { toolCall("wait"); reply("unreachable") } },
            scenario = { session = session(agent("work")) },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val changes = mutableListOf<SessionChange>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).filter { it.sessionId == session.id }.collect { synchronized(changes) { changes += it.change } } }
                client.call(Api.Subscribe, SubscribeParams(AgentId("work"), session.id))
                client.call(Api.Send, SendParams(AgentId("work"), session.id, "hold"))
                awaitCondition("the run to start", 5.seconds) { synchronized(changes) { changes.any { (it as? SessionChange.Run)?.event is AgUiEvent.RunStarted } } }
                client.call(Api.Stop, StopParams(session.id))
                awaitCondition("the run to end", 5.seconds) { synchronized(changes) { changes.any { (it as? SessionChange.Run)?.event is AgUiEvent.RunFinished } } }
                collector.cancel()
                val run = synchronized(changes) { changes.filterIsInstance<SessionChange.Run>().map { it.event } }
                conform(run)
                assertEquals(RunOutcome.Cancelled, assertIs<AgUiEvent.RunFinished>(run.last()).outcome)
            }
        }
    }

    private suspend fun snapshotOf(client: KinuvoClient, session: SessionInfo): SessionSnapshot = coroutineScope {
        val snapshot = async(start = CoroutineStart.UNDISPATCHED) {
            client.events(Api.Update).filter { it.sessionId == session.id }.mapNotNull { (it.change as? SessionChange.Snapshot)?.snapshot }.first()
        }
        client.call(Api.Subscribe, SubscribeParams(AgentId("work"), session.id))
        snapshot.await()
    }
}
