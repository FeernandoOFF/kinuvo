@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.gateways.acp

import com.agentclientprotocol.agent.v2.*
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.rpc.ACPJson
import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.approval.QuestionKind
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.RespondParams
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One selected native Agent; only Sessions created by this connection are reachable. */
class KinuvoAgent(private val dispatch: ExecutionDispatch, private val agentId: String) : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo) = com.agentclientprotocol.agent.v2.AgentInfo(Implementation("kinuvo", "0.1"))

    override suspend fun createSession(parameters: SessionCreationParameters, client: ClientOperations): AgentSession {
        require(parameters.mcpServers.isEmpty() && parameters.additionalDirectories.isEmpty()) { "Client MCP servers and additional directories are unsupported" }
        return NativeSession(dispatch.create(agentId).id, client)
    }

    private inner class NativeSession(private val id: String, private val client: ClientOperations) : AgentSession {
        override val sessionId = SessionId(id)
        @Volatile private var cancelled = false
        private val commands = Mutex()
        override suspend fun cancel() = commands.withLock { cancelled = true; dispatch.stop(id) }
        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<SessionUpdate> {
            if (content.isEmpty() || content.any { it !is ContentBlock.Text }) jsonRpcInvalidParams("Only text prompts are supported")
            cancelled = false
            return flow {
                val previous = dispatch.snapshot(agentId, id).messages.associateBy { it.id }.toMutableMap()
                commands.withLock {
                    if (!cancelled) dispatch.send(SendParams(agentId, id, content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }))
                }
                emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
                val answered = mutableSetOf<String>()
                coroutineScope {
                    while (true) {
                        val snapshot = dispatch.snapshot(agentId, id)
                        for (message in snapshot.messages) {
                            if (previous.put(message.id, message) == message) continue
                            val blocks = MaybeUndefined.Value(listOf(ContentBlock.Text(message.text)))
                            emit(when (message.role) {
                                "user" -> SessionUpdate.UserMessage(UserMessage(MessageId(message.id), blocks))
                                "reasoning" -> SessionUpdate.AgentThought(AgentThought(MessageId(message.id), blocks))
                                "tool" -> {
                                    val entry = message.payload?.let { ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), it) }
                                    when (entry) {
                                        is SessionEntry.ToolCall -> SessionUpdate.ToolCallUpdate(ToolCallUpdate(ToolCallId(entry.id), title = MaybeUndefined.Value(entry.name), status = MaybeUndefined.Value(ToolCallStatus.InProgress), rawInput = MaybeUndefined.Value(JsonPrimitive(entry.args))))
                                        is SessionEntry.ToolResult -> SessionUpdate.ToolCallUpdate(ToolCallUpdate(ToolCallId(entry.id), status = MaybeUndefined.Value(if (entry.isError) ToolCallStatus.Failed else ToolCallStatus.Completed), rawOutput = MaybeUndefined.Value(JsonPrimitive(entry.output))))
                                        else -> error("Unexpected native tool entry")
                                    }
                                }
                                else -> SessionUpdate.AgentMessage(AgentMessage(MessageId(message.id), blocks))
                            })
                        }
                        when (snapshot.phase) {
                            ExecutionPhase.RequiresAction -> emit(SessionUpdate.StateUpdate(StateUpdate.RequiresAction()))
                            ExecutionPhase.Running -> emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
                            ExecutionPhase.Lost -> error("Connection to native execution was lost")
                            ExecutionPhase.Idle -> {
                                emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = if (cancelled) StopReason.Cancelled else StopReason.EndTurn)))
                                break
                            }
                        }
                        for (request in snapshot.pending) if (answered.add(request.id)) launch {
                            val answer = when (request) {
                                is PendingInteraction.HarnessApproval -> approve(request)
                                is PendingInteraction.Questions -> answerQuestions(request)
                                else -> InteractionAnswer.Harness(JsonNull)
                            }
                            dispatch.answer(RespondParams(id, request.id, answer))
                        }
                        delay(100)
                    }
                    coroutineContext.cancelChildren()
                }
            }
        }

        private suspend fun approve(request: PendingInteraction.HarnessApproval): InteractionAnswer {
            val options = request.options.map {
                PermissionOption(PermissionOptionId(it.id), it.label, ACPJson.decodeFromJsonElement(PermissionOptionKind.serializer(), JsonPrimitive(it.kind)))
            }
            val response = client.requestPermission("Kinuvo Approval", options, description = listOfNotNull(request.title, request.description).joinToString(": "))
            val outcome = ACPJson.encodeToJsonElement(RequestPermissionResponse.serializer(), response).jsonObject["outcome"] as? JsonObject
            val option = outcome?.takeIf { it["outcome"]?.jsonPrimitive?.content == "selected" }?.get("optionId")
            return InteractionAnswer.Harness(option?.let { buildJsonObject { put("optionId", it) } } ?: JsonNull)
        }

        private suspend fun answerQuestions(asked: PendingInteraction.Questions): InteractionAnswer {
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { asked.questions.forEach { q -> put(q.id, buildJsonObject {
                    put("title", q.label)
                    if (q.kind == QuestionKind.Multi) {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "string")
                            if (q.options.isNotEmpty()) put("enum", JsonArray(q.options.map(::JsonPrimitive)))
                        })
                        put("uniqueItems", true)
                    } else {
                        put("type", "string")
                        if (q.options.isNotEmpty()) put("enum", JsonArray(q.options.map(::JsonPrimitive)))
                    }
                }) } })
                put("required", JsonArray(asked.questions.filter { it.required }.map { JsonPrimitive(it.id) }))
            }
            val mode = ElicitationMode.Form(ElicitationScope.Session(sessionId), ACPJson.decodeFromJsonElement(ElicitationSchema.serializer(), schema))
            val response = client.createElicitation(asked.title ?: "Kinuvo questions", mode)
            val raw = ACPJson.encodeToJsonElement(CreateElicitationResponse.serializer(), response).jsonObject
            val answers = if (raw["action"]?.jsonPrimitive?.content == "accept") raw["content"]?.jsonObject?.mapValues { (_, value) ->
                (value as? JsonArray ?: JsonArray(listOf(value))).map { (it as? JsonPrimitive)?.content.orEmpty() }
            } else null
            return InteractionAnswer.Questions(answers)
        }
    }
}
