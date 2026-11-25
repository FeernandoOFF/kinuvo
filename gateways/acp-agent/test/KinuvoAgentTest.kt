@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package kinuvo.gateways.acp

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.client.v2.*
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*
import java.util.concurrent.atomic.AtomicInteger
import com.agentclientprotocol.model.v2.SessionUpdate

class KinuvoAgentTest {
    @Test fun sdkClientSeesAcceptanceOutputActionsCompletionAndCancel() = runBlocking {
        withTimeout(15000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val toAgent = Channel<String>(Channel.UNLIMITED)
            val toClient = Channel<String>(Channel.UNLIMITED)
            val server = Protocol(scope, StdioTransport(scope, Dispatchers.Default, toAgent.receiveAsFlow(), toClient::send))
            val peer = Protocol(scope, StdioTransport(scope, Dispatchers.Default, toClient.receiveAsFlow(), toAgent::send))
            val backend = FakeExecution()
            Agent(server, KinuvoAgent(backend, AgentId("native")))
            val client = Client(peer, object : ElicitationHandler {
                override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse =
                    CreateElicitationResponse(ElicitationAction.Accept(mapOf("name" to ElicitationContentValue.StringValue("Ada"))))
            })
            try {
                server.start(); peer.start()
                client.initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("test", "1")))
                val session = client.newSession("/tmp", operations = object : ClientSessionOperations {
                    override suspend fun requestPermission(request: RequestPermissionRequest) = RequestPermissionResponse(RequestPermissionOutcome.Selected(request.options.first().optionId))
                })
                val updates = Channel<SessionUpdate>(Channel.UNLIMITED)
                val collecting = scope.launch { session.updates.collect { updates.send(it.update) } }
                session.prompt(listOf(ContentBlock.Text("hello")))
                backend.started.await()
                assertEquals(ExecutionPhase.Running, backend.state.phase)
                backend.state = backend.state.copy(phase = ExecutionPhase.RequiresAction, pending = listOf(PendingInteraction.HarnessApproval(InteractionId("approval"), "Run", null, listOf(ExternalApprovalOption("allow_once", "Allow once", "allow_once")))))
                backend.approval.await()
                backend.state = backend.state.copy(phase = ExecutionPhase.RequiresAction, pending = listOf(PendingInteraction.Questions(InteractionId("form"), ToolCallId("tool"), "Name", listOf(Question("name", "Name")))))
                backend.form.await()
                assertEquals(mapOf("name" to listOf("Ada")), (backend.answers.last().answer as InteractionAnswer.Questions).answers)
                assertEquals(InteractionAnswer.Harness(buildJsonObject { put("optionId", "allow_once") }), backend.answers.first().answer)
                backend.state = backend.state.copy(phase = ExecutionPhase.RequiresAction, pending = listOf(PendingInteraction.Connect(InteractionId("link"), ToolCallId("tool"), AgentId("native"), "service")))
                backend.link.await()
                assertEquals(InteractionAnswer.Connect(false), backend.answers.last().answer)
                while (backend.answers.count { it.requestId == InteractionId("link") } < 2) delay(50)
                assertEquals(ExecutionPhase.Running, backend.state.phase, "a failed answer is retried while the interaction is pending")
                backend.state = ExecutionSnapshot(SessionId("s"), messages = listOf(ExecutionMessage("u", "user", "hello"), ExecutionMessage("a", "assistant", "reply"), ExecutionMessage("t", "tool", "tool", ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), SessionEntry.ToolCall(ToolCallId("t"), "tool", "{}")))))
                val received = mutableListOf<SessionUpdate>()
                do { val update = updates.receive(); received.add(update) } while (update !is SessionUpdate.StateUpdate || update.state !is StateUpdate.Idle)
                assertTrue(received.any { it is SessionUpdate.AgentMessage })
                assertTrue(received.any { it is SessionUpdate.ToolCallUpdate })
                assertTrue(received.any { it is SessionUpdate.UserMessage })
                session.prompt(listOf(ContentBlock.Text("wait")))
                session.cancel()
                var terminal: StateUpdate.Idle? = null
                while (terminal == null) {
                    val update = updates.receive()
                    terminal = (update as? SessionUpdate.StateUpdate)?.state as? StateUpdate.Idle
                }
                assertEquals(StopReason.Cancelled, terminal.stopReason)
                assertEquals(listOf("s"), backend.stopped)
                collecting.cancel()
            } finally { server.close(); peer.close(); scope.cancel() }
        }
    }

    private val approval = PendingInteraction.HarnessApproval(InteractionId("ask"), "Run", null, listOf(ExternalApprovalOption("allow_once", "Allow once", "allow_once")))

    private fun acp(backend: FakeExecution, prompts: AtomicInteger, block: suspend () -> Unit) = runBlocking {
        withTimeout(15000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val toAgent = Channel<String>(Channel.UNLIMITED)
            val toClient = Channel<String>(Channel.UNLIMITED)
            val server = Protocol(scope, StdioTransport(scope, Dispatchers.Default, toAgent.receiveAsFlow(), toClient::send))
            val peer = Protocol(scope, StdioTransport(scope, Dispatchers.Default, toClient.receiveAsFlow(), toAgent::send))
            Agent(server, KinuvoAgent(backend, AgentId("native")))
            val client = Client(peer, object : ElicitationHandler {
                override suspend fun createElicitation(request: CreateElicitationRequest) = CreateElicitationResponse(ElicitationAction.Decline)
            })
            try {
                server.start(); peer.start()
                client.initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("test", "1")))
                val session = client.newSession("/tmp", operations = object : ClientSessionOperations {
                    override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
                        prompts.incrementAndGet()
                        return RequestPermissionResponse(RequestPermissionOutcome.Selected(request.options.first().optionId))
                    }
                })
                session.prompt(listOf(ContentBlock.Text("hello")))
                backend.started.await()
                backend.state = backend.state.copy(phase = ExecutionPhase.RequiresAction, pending = listOf(approval))
                block()
            } finally { server.close(); peer.close(); scope.cancel() }
        }
    }

    @Test fun failedDeliveryRetriesTheCachedAnswerWithoutPromptingAgain() {
        val backend = FakeExecution().apply { refuse = 2 }
        val prompts = AtomicInteger()
        acp(backend, prompts) {
            backend.approval.await()
            assertEquals(3, backend.answers.size)
            assertEquals(1, prompts.get())
            assertEquals(1, backend.answers.map { it.answer }.toSet().size)
        }
    }

    @Test fun persistentFailureEndsThePromptAndStopsTheExecution() {
        val backend = FakeExecution().apply { refuse = Int.MAX_VALUE }
        val prompts = AtomicInteger()
        acp(backend, prompts) {
            while (backend.stopped.isEmpty()) delay(50)
            delay(500)
            assertEquals(3, backend.answers.size)
            assertEquals(1, prompts.get())
            assertEquals(listOf("s"), backend.stopped)
        }
    }

    @Test fun unstagedInteractionIsRedeliveredFromTheCache() {
        val backend = FakeExecution().apply { keepPending = true }
        val prompts = AtomicInteger()
        acp(backend, prompts) {
            while (backend.answers.isEmpty()) delay(50)
            backend.state = backend.state.copy(staged = setOf(approval.id))
            delay(500)
            assertEquals(1, backend.answers.size, "a staged answer is not resent")
            backend.state = backend.state.copy(staged = emptySet())
            while (backend.answers.size < 2) delay(50)
            assertEquals(1, prompts.get())
            assertEquals(backend.answers[0], backend.answers[1])
        }
    }

    private class FakeExecution : ExecutionDispatch {
        @Volatile var state = ExecutionSnapshot(SessionId("s"))
        val answers = java.util.concurrent.CopyOnWriteArrayList<RespondParams>()
        @Volatile var refuse = 0
        @Volatile var keepPending = false
        val stopped = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        val form = CompletableDeferred<Unit>()
        val link = CompletableDeferred<Unit>()
        override suspend fun create(agentId: AgentId) = kinuvo.protocol.session.SessionInfo(SessionId("s"), agentId, "ACP")
        override suspend fun snapshot(agentId: AgentId, sessionId: SessionId) = state
        override suspend fun send(params: SendParams): SendResult { state = state.copy(phase = ExecutionPhase.Running); started.complete(Unit); return SendResult(SessionId("s"), RunId("run"), "accepted") }
        override suspend fun stop(sessionId: SessionId) { stopped.add(sessionId.value); state = state.copy(phase = ExecutionPhase.Idle) }
        override suspend fun answer(answer: RespondParams): Boolean {
            answers.add(answer)
            if (answer.requestId == InteractionId("ask")) {
                if (refuse-- > 0) throw IllegalArgumentException("refused")
                if (keepPending) return true
                state = state.copy(phase = ExecutionPhase.Running, pending = emptyList()); approval.complete(Unit); return true
            }
            if (answer.requestId == InteractionId("link") && answers.count { it.requestId == answer.requestId } == 1) { link.complete(Unit); throw IllegalArgumentException("refused") }
            state = state.copy(phase = ExecutionPhase.Running, pending = emptyList())
            when (answer.requestId) {
                InteractionId("approval") -> approval.complete(Unit)
                InteractionId("link") -> Unit
                else -> form.complete(Unit)
            }
            return true
        }
    }
}
