package kinuvo.gateways.agui

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondText
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
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.LiveState
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.SubscribeParams
import kinuvo.protocol.session.UnsubscribeParams
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    private val openInterrupts = ConcurrentHashMap<String, Set<String>>()
    private val sendRuns = ConcurrentHashMap.newKeySet<String>()
    private val creating = Mutex()
    private val subscriptions = Mutex()
    private val subscribers = HashMap<SessionId, Int>()

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
        val answers = if (resume.isEmpty()) emptyList() else runCatching { answers(input.threadId, resume) }.getOrElse { return@coroutineScope fail(it.message ?: it.toString()) }

        val changes = Channel<SessionChange>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events(Api.Update).filter { it.sessionId == sessionId }.collect { changes.send(it.change) }
        }
        var subscribed = false
        try {
            subscribe(sessionId)
            subscribed = true
            var state: LiveState? = null
            withTimeoutOrNull(startTimeout) { for (change in changes) if (change is SessionChange.Snapshot) { state = change.snapshot.state; break } }
            val held = ArrayDeque<SessionChange>()
            fun settle(keepRuns: Boolean) {
                while (true) {
                    val change = changes.tryReceive().getOrNull() ?: break
                    if (change is SessionChange.Item) state = state?.on(change.event)
                    if (keepRuns) held += change
                }
            }
            suspend fun next(): SessionChange? = held.removeFirstOrNull() ?: changes.receiveCatching().getOrNull()
            var sent: String? = null
            val error = runCatching {
                if (resume.isEmpty()) {
                    sent = client.call(Api.Send, SendParams(agentId, sessionId, text.orEmpty())).runId.value.also { sendRuns += it }
                } else {
                    settle(keepRuns = false)
                    val live = checkNotNull(state) { "The Session did not respond" }
                    val open = openInterrupts[input.threadId].orEmpty().map(::InteractionId)
                    val answered = answers.map { InteractionId(it.first) }.toSet()
                    val waiting = live.pending.map { it.id }.toSet() - live.staged
                    val missing = open.filter { it in waiting && it !in answered }
                    require(missing.isEmpty()) { "Resume must answer every open interrupt; missing: ${missing.joinToString { it.value }}" }
                    check(waiting.any { it in open } || live.staged.any { it in open } || live.running) { "The run was stopped" }
                    answers.filter { (id, _) -> InteractionId(id) in waiting }.forEach { (id, answer) ->
                        if (!client.call(Api.Respond, RespondParams(sessionId, InteractionId(id), answer))) {
                            settle(keepRuns = true)
                            val now = checkNotNull(state)
                            check(now.pending.none { it.id.value == id } || InteractionId(id) in now.staged) { "Interrupt $id could not be answered: the run was stopped or it was answered elsewhere" }
                        }
                    }
                    openInterrupts.remove(input.threadId)
                }
            }.exceptionOrNull()
            if (error != null) return@coroutineScope fail(error.message ?: error.toString())

            val first = try {
                withTimeoutOrNull(startTimeout) {
                    var started: AgUiEvent.RunStarted? = null
                    while (started == null) {
                        when (val change = next() ?: break) {
                            is SessionChange.Run -> (change.event as? AgUiEvent.RunStarted)?.let { event ->
                                if (sent != null) { if (event.runId == sent) started = event }
                                else if (event.runId in sendRuns) throw IllegalStateException("The run was stopped") else started = event
                            }
                            is SessionChange.Item -> if (sent == null && change.event is AgentEvent.Idle) throw IllegalStateException("The run was stopped")
                            else -> Unit
                        }
                    }
                    started
                }
            } catch (e: IllegalStateException) {
                return@coroutineScope fail(e.message.orEmpty())
            } ?: return@coroutineScope fail(if (sent != null) "The message is queued behind another run and has not started" else "The Agent did not start a run")
            var event: AgUiEvent? = first
            while (event != null) {
                emit(event.forThread(input))
                if (event is AgUiEvent.RunFinished || event is AgUiEvent.RunError) break
                event = nextRun(::next)
            }
        } finally {
            collector.cancel()
            changes.close()
            if (subscribed) unsubscribe(sessionId)
        }
    }

    private suspend fun subscribe(sessionId: SessionId) = subscriptions.withLock {
        client.call(Api.Subscribe, SubscribeParams(agentId, sessionId))
        subscribers.merge(sessionId, 1, Int::plus)
    }

    private suspend fun unsubscribe(sessionId: SessionId) = withContext(NonCancellable) {
        subscriptions.withLock {
            if (subscribers.merge(sessionId, -1, Int::plus) == 0) {
                subscribers.remove(sessionId)
                runCatching { client.call(Api.Unsubscribe, UnsubscribeParams(sessionId)) }
            }
        }
    }

    private fun answers(threadId: String, resume: List<ResumeEntry>): List<Pair<String, InteractionAnswer>> {
        val open = openInterrupts[threadId].orEmpty()
        val ids = resume.map { it.interruptId }
        require(open.isNotEmpty()) { "Thread $threadId has no open interrupts" }
        require(ids.size == ids.toSet().size) { "Each interrupt may be answered once" }
        require(open.containsAll(ids)) { "Unknown interrupts: ${(ids.toSet() - open).joinToString()}" }
        return resume.map { it.interruptId to answer(it) }
    }

    private suspend fun nextRun(next: suspend () -> SessionChange?): AgUiEvent? {
        while (true) (next() ?: return null).let { if (it is SessionChange.Run) return it.event }
    }

    private fun AgUiEvent.forThread(input: RunAgentInput): AgUiEvent = when (this) {
        is AgUiEvent.RunStarted -> copy(threadId = input.threadId, runId = input.runId)
        is AgUiEvent.RunFinished -> {
            (outcome as? RunOutcome.Interrupted)?.let { interrupted ->
                interrupted.interrupts.forEach { reasons[it.id] = it.reason }
                openInterrupts[input.threadId] = interrupted.interrupts.mapTo(mutableSetOf()) { it.id }
            }
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
                    "allow_once" -> ApprovalDecision.AllowOnce
                    "deny" -> ApprovalDecision.Deny(payload["note"]?.jsonPrimitive?.contentOrNull)
                    else -> throw IllegalArgumentException("Interrupt ${entry.interruptId} needs payload.decision: allow_once, allow_session, always_allow or deny")
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

private val LOOPBACK_HOSTS = listOf("127.0.0.1", "localhost", "[::1]")

/** Binds loopback only. Requests need a loopback `Host`, no `Origin` unless it is in [allowedOrigins], and a JSON body. */
suspend fun startAgUi(client: KinuvoClient, agentId: AgentId, host: String, port: Int, startTimeout: Duration = 30.seconds, allowedOrigins: Set<String> = emptySet()): AgUiServer {
    require(InetAddress.getByName(host).isLoopbackAddress) { "The AG-UI gateway only listens on loopback addresses, not $host" }
    val origins = allowedOrigins.map { it.trimEnd('/').lowercase() }.toSet()
    val bound = AtomicInteger()
    val gateway = AgUiGateway(client, agentId, startTimeout)
    val engine = embeddedServer(CIO, host = host, port = port) {
        intercept(ApplicationCallPipeline.Plugins) {
            val origin = call.request.headers[HttpHeaders.Origin]
            val hostAllowed = call.request.headers[HttpHeaders.Host]?.lowercase() in LOOPBACK_HOSTS.map { "$it:${bound.get()}" }
            val denied = when {
                !hostAllowed -> "Host not allowed"
                origin != null && origin.trimEnd('/').lowercase() !in origins -> "Origin not allowed"
                else -> null
            }
            if (denied != null) {
                call.respondText(denied, status = HttpStatusCode.Forbidden)
                finish()
            }
        }
        install(CORS) {
            origins.forEach { origin -> URI(origin).let { allowHost(it.authority, listOf(it.scheme)) } }
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
        }
        routing {
            post("/") {
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                    call.respondText("Content-Type must be application/json", status = HttpStatusCode.UnsupportedMediaType)
                    return@post
                }
                val body = call.receiveText()
                val input = runCatching { ProtocolJson.decodeFromString(RunAgentInput.serializer(), body) }.getOrElse {
                    val ids = runCatching { ProtocolJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    fun id(key: String) = (ids?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.BadRequest) {
                        write(sse(AgUiEvent.RunStarted(id("threadId"), id("runId"))))
                        write(sse(AgUiEvent.RunError(it.message ?: "Invalid input")))
                    }
                    return@post
                }
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(ContentType.Text.EventStream) {
                    gateway.run(input) { event -> write(sse(event)); flush() }
                }
            }
        }
    }.start(wait = false)
    val boundPort = engine.engine.resolvedConnectors().first().port
    bound.set(boundPort)
    return AgUiServer(engine, boundPort)
}

suspend fun serveAgUi(client: KinuvoClient, agentId: AgentId, host: String, port: Int, allowedOrigins: Set<String> = emptySet()) {
    val server = startAgUi(client, agentId, host, port, allowedOrigins = allowedOrigins)
    try { awaitCancellation() } finally { server.stop() }
}
