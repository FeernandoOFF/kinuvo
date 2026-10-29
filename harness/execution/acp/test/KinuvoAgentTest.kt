@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package kinuvo.execution.acp

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.client.v2.*
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kinuvo.execution.api.ExecutionDispatch
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*
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
            Agent(server, KinuvoAgent(backend, "native"))
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
                backend.state = backend.state.copy(phase = ExecutionPhase.RequiresAction, pending = listOf(ExecutionRequest("approval", ExecutionRequestKind.Approval, JsonObject(emptyMap()), listOf(ExternalApprovalOption("allow_once", "Allow once", "allow_once")))))
                backend.approval.await()
                backend.state = backend.state.copy(phase = ExecutionPhase.RequiresAction, pending = listOf(ExecutionRequest("form", ExecutionRequestKind.Question, ProtocolJson.encodeToJsonElement(AgentEvent.QuestionsAsked.serializer(), AgentEvent.QuestionsAsked("form", "tool", "Name", listOf(Question("name", "Name")))))))
                backend.form.await()
                assertEquals("Ada", backend.answers.last().answer.jsonObject["answers"]!!.jsonObject["name"]!!.jsonArray.single().jsonPrimitive.content)
                backend.state = ExecutionSnapshot("s", messages = listOf(ExecutionMessage("u", "user", "hello"), ExecutionMessage("a", "assistant", "reply"), ExecutionMessage("t", "tool", "tool", ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), SessionEntry.ToolCall("t", "tool", "{}")))))
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

    private class FakeExecution : ExecutionDispatch {
        @Volatile var state = ExecutionSnapshot("s")
        val answers = mutableListOf<ExecutionAnswer>()
        val stopped = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        val form = CompletableDeferred<Unit>()
        override suspend fun create(agentId: String) = kinuvo.protocol.SessionInfo("s", agentId, "ACP")
        override suspend fun snapshot(agentId: String, sessionId: String) = state
        override suspend fun send(params: SendParams): SendResult { state = state.copy(phase = ExecutionPhase.Running); started.complete(Unit); return SendResult("s", "run", "accepted") }
        override suspend fun stop(sessionId: String) { stopped.add(sessionId); state = state.copy(phase = ExecutionPhase.Idle) }
        override suspend fun answer(answer: ExecutionAnswer): Boolean {
            answers.add(answer); state = state.copy(phase = ExecutionPhase.Running, pending = emptyList())
            if (answer.requestId == "approval") approval.complete(Unit) else form.complete(Unit)
            return true
        }
    }
}
