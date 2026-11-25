package kinuvo.gateways.agui

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.RunOutcome
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.SubscribeParams
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The parts of an AG-UI `RunAgentInput` the gateway reads; tools, context, state and forwardedProps are ignored. */
@Serializable
data class RunAgentInput(val threadId: String, val runId: String, val messages: List<InputMessage> = emptyList(), val resume: List<ResumeEntry>? = null)

@Serializable
data class InputMessage(val role: String, val content: JsonElement? = null) {
    val text: String get() = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
        else -> ""
    }
}

@Serializable
data class ResumeEntry(val interruptId: String, val status: String, val payload: JsonElement? = null)

/** Runs one Agent for AG-UI clients: one Kinuvo Session per thread, one SSE stream per run. */
class AgUiGateway(private val client: KinuvoClient, private val agentId: AgentId, private val startTimeout: Duration = 30.seconds) {
    private val sessions = ConcurrentHashMap<String, SessionId>()
    private val reasons = ConcurrentHashMap<String, String>()
    private val creating = Mutex()

    suspend fun run(input: RunAgentInput, emit: suspend (AgUiEvent) -> Unit) = coroutineScope {
        suspend fun fail(message: String) {
            emit(AgUiEvent.RunStarted(input.threadId, input.runId))
            emit(AgUiEvent.RunError(message))
        }
        val resume = input.resume.orEmpty()
        val text = input.messages.lastOrNull { it.role == "user" }?.text
        if (resume.isEmpty() && text == null) return@coroutineScope fail("No user message")
        val sessionId = creating.withLock {
            sessions[input.threadId] ?: if (resume.isNotEmpty()) null else client.call(Api.CreateSession, AgentRef(agentId)).id.also { sessions[input.threadId] = it }
        } ?: return@coroutineScope fail("Unknown thread ${input.threadId}")

        val changes = Channel<SessionChange>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events(Api.Update).filter { it.sessionId == sessionId }.collect { changes.send(it.change) }
        }
        try {
            client.call(Api.Subscribe, SubscribeParams(agentId, sessionId))
            withTimeoutOrNull(startTimeout) { for (change in changes) if (change is SessionChange.Snapshot) break }
            val error = runCatching {
                if (resume.isEmpty()) client.call(Api.Send, SendParams(agentId, sessionId, text.orEmpty()))
                else resume.forEach { check(client.call(Api.Respond, RespondParams(sessionId, InteractionId(it.interruptId), answer(it)))) { "Interrupt ${it.interruptId} was already answered" } }
            }.exceptionOrNull()
            if (error != null) return@coroutineScope fail(error.message ?: error.toString())

            val first = withTimeoutOrNull(startTimeout) { changes.nextRun { it is AgUiEvent.RunStarted } }
                ?: return@coroutineScope fail("The Agent did not start a run")
            var event: AgUiEvent? = first
            while (event != null) {
                emit(event.forThread(input))
                if (event is AgUiEvent.RunFinished || event is AgUiEvent.RunError) break
                event = changes.nextRun { true }
            }
        } finally {
            collector.cancel()
            changes.close()
        }
    }

    private suspend fun Channel<SessionChange>.nextRun(accept: (AgUiEvent) -> Boolean): AgUiEvent? {
        for (change in this) (change as? SessionChange.Run)?.event?.takeIf(accept)?.let { return it }
        return null
    }

    private fun AgUiEvent.forThread(input: RunAgentInput): AgUiEvent = when (this) {
        is AgUiEvent.RunStarted -> copy(threadId = input.threadId, runId = input.runId)
        is AgUiEvent.RunFinished -> {
            (outcome as? RunOutcome.Interrupted)?.interrupts?.forEach { reasons[it.id] = it.reason }
            copy(threadId = input.threadId, runId = input.runId)
        }
        else -> this
    }

    private fun answer(entry: ResumeEntry): InteractionAnswer {
        val payload = entry.payload as? JsonObject
        val cancelled = entry.status == "cancelled"
        return when (reasons[entry.interruptId]) {
            "input_required" -> InteractionAnswer.Questions(if (cancelled) null else payload?.mapValues { (_, v) -> v.answerValues() })
            "kinuvo:connect" -> InteractionAnswer.Connect(!cancelled && payload?.get("connected")?.jsonPrimitive?.contentOrNull != "false")
            "kinuvo:harness_approval", "kinuvo:open_link", "kinuvo:unsupported" ->
                InteractionAnswer.Harness(if (cancelled) JsonNull else payload ?: JsonNull)
            else -> InteractionAnswer.Approval(
                if (cancelled) ApprovalDecision.Deny() else when (payload?.get("decision")?.jsonPrimitive?.contentOrNull) {
                    "allow_session" -> ApprovalDecision.AllowForSession
                    "always_allow" -> ApprovalDecision.AlwaysAllow
                    "deny" -> ApprovalDecision.Deny(payload["note"]?.jsonPrimitive?.contentOrNull)
                    else -> ApprovalDecision.AllowOnce
                },
            )
        }
    }

    private fun JsonElement.answerValues(): List<String> = when (this) {
        is JsonArray -> map { it.jsonPrimitive.content }
        is JsonPrimitive -> listOf(content)
        else -> listOf(toString())
    }
}

class AgUiServer internal constructor(private val engine: EmbeddedServer<*, *>, val port: Int) {
    fun stop() = engine.stop(0, 0)
}

private fun sse(event: AgUiEvent) = "data: ${ProtocolJson.encodeToString(AgUiEvent.serializer(), event)}\n\n"

suspend fun startAgUi(client: KinuvoClient, agentId: AgentId, host: String, port: Int, startTimeout: Duration = 30.seconds): AgUiServer {
    val gateway = AgUiGateway(client, agentId, startTimeout)
    val engine = embeddedServer(CIO, host = host, port = port) {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
        }
        routing {
            post("/") {
                val input = runCatching { ProtocolJson.decodeFromString(RunAgentInput.serializer(), call.receiveText()) }.getOrElse {
                    call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.BadRequest) { write(sse(AgUiEvent.RunError(it.message ?: "Invalid input"))) }
                    return@post
                }
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(ContentType.Text.EventStream) {
                    gateway.run(input) { event -> write(sse(event)); flush() }
                }
            }
        }
    }.start(wait = false)
    return AgUiServer(engine, engine.engine.resolvedConnectors().first().port)
}

suspend fun serveAgUi(client: KinuvoClient, agentId: AgentId, host: String, port: Int) {
    val server = startAgUi(client, agentId, host, port)
    try { awaitCancellation() } finally { server.stop() }
}
