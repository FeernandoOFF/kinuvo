package kinuvo.providers.codex

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kinuvo.server.plugin.LiveCall
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.LiveCallException
import kinuvo.server.plugin.LiveCallProvider
import kinuvo.server.plugin.LiveCallUnavailable
import kinuvo.server.plugin.LiveCallStart
import kinuvo.server.plugin.VoiceContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("codex.live")

/** HTTP client for [CodexLiveCalls]: the call is created over HTTP and controlled over a WebSocket. */
fun codexLiveHttpClient() = HttpClient(codexEngine()) { install(WebSockets) }

/**
 * Live calls over the ChatGPT subscription whose WebRTC connection the Device holds: Codex's realtime "frameless" (V3)
 * protocol. The Server creates the call with its token, which never reaches the Device, and controls it over a
 * "sideband" WebSocket. Pure HTTP and WebSocket, so every Server platform runs it. Undocumented backend, mirrored from
 * the Codex CLI: an invalid [voice] still creates the call but it never activates.
 */
class CodexLiveCalls(
    private val auth: CodexAuth,
    private val http: HttpClient,
    private val providerId: String = "codex",
    private val voice: String = "cove",
    private val model: String = "gpt-live-1-codex",
    private val callsUrl: String = CALLS_URL,
    private val sidebandUrl: String = SIDEBAND_URL,
) : LiveCallProvider {
    private val sidebands = ktorSidebands(http)

    override suspend fun available() = auth.signInKind() == ChatGptSignInKind.Codex

    override suspend fun unavailable() = LiveCallUnavailable(DEVICE_CODE_REASON, providerId).takeIf { auth.signInKind() == ChatGptSignInKind.Browser }

    override suspend fun start(context: VoiceContext, offerSdp: String): LiveCallStart {
        val session = Uuid.random().toString()
        val response = post(createBody(context, offerSdp), session)
        log.info { "Call created: ${response.status}" }
        if (!response.status.isSuccess()) {
            val detail = response.bodyAsText()
            log.warn { "Realtime call failed (${response.status}): ${detail.take(500)}" }
            throw LiveCallException(callFailure(response.status.value, detail))
        }
        val id = callId(response.headers[HttpHeaders.Location]) ?: run {
            log.warn { "Realtime call has no Location header" }
            throw LiveCallException(NO_CALL)
        }
        return LiveCallStart(response.bodyAsText(), CodexLiveCall(id, session, auth, "$sidebandUrl$id", sidebands))
    }

    private fun createBody(context: VoiceContext, sdp: String) = buildJsonObject {
        put("sdp", sdp)
        put("session", buildJsonObject {
            put("model", model)
            put("instructions", context.instructions)
            put("initial_items", buildJsonArray {
                for (message in context.history) add(buildJsonObject {
                    put("type", "message")
                    put("role", if (message.user) "user" else "assistant")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", if (message.user) "input_text" else "output_text"); put("text", message.text) }) })
                })
            })
            put("audio", buildJsonObject { put("output", buildJsonObject { put("voice", voice) }) })
            put("delegation", buildJsonObject { put("type", "client") })
        })
    }.toString()

    private suspend fun post(body: String, session: String): HttpResponse {
        suspend fun send(forceRefresh: Boolean): HttpResponse {
            val tokens = codexTokens(auth, forceRefresh)
            return try { http.post(callsUrl) {
                realtimeHeaders(tokens, session)
                contentType(ContentType.Application.Json)
                setBody(body)
            } } catch (e: IOException) {
                log.warn(e) { "Realtime call unreachable" }
                throw LiveCallException(UNREACHABLE, e)
            }
        }
        val first = send(forceRefresh = false)
        return if (first.status == HttpStatusCode.Unauthorized && !accessRejected(first.bodyAsText())) send(forceRefresh = true) else first
    }

    companion object {
        const val CALLS_URL = "${CODEX_BASE_URL}realtime/calls?intent=quicksilver&architecture=avas"
        const val SIDEBAND_URL = "wss://api.openai.com/v1/live/"
        private const val NO_CALL = "Couldn't start the call."

        internal fun callId(location: String?) = location?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    }
}

private suspend fun codexTokens(auth: CodexAuth, forceRefresh: Boolean = false): CodexTokens = try { auth.tokens(forceRefresh) }
catch (e: CancellationException) { throw e }
catch (e: IllegalStateException) {
    log.warn(e) { "Realtime call has no ChatGPT login" }
    throw LiveCallException(SIGN_IN, e)
}

private fun HttpRequestBuilder.realtimeHeaders(tokens: CodexTokens, session: String) {
    header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
    if (tokens.accountId.isNotBlank()) header("chatgpt-account-id", tokens.accountId)
    header("OpenAI-Alpha", "quicksilver=v2")
    header("originator", "kinuvo")
    header("session-id", session)
    header("x-session-id", session)
}

/** The side channel of a call: its frames in and out. */
internal interface Sideband {
    val incoming: Flow<String>
    suspend fun send(text: String)
}

internal fun interface Sidebands {
    suspend fun connect(url: String, tokens: CodexTokens, session: String, block: suspend (Sideband) -> Unit)
}

internal fun ktorSidebands(http: HttpClient) = Sidebands { url, tokens, session, block ->
    http.webSocket(url, request = { realtimeHeaders(tokens, session) }) {
        val socket = this
        block(object : Sideband {
            override val incoming = socket.incoming.receiveAsFlow().filterIsInstance<Frame.Text>().map { it.readText() }
            override suspend fun send(text: String) = socket.send(Frame.Text(text))
        })
    }
}

internal class CodexLiveCall(
    private val id: String,
    private val session: String,
    private val auth: CodexAuth,
    private val url: String,
    private val sidebands: Sidebands,
    private val holdMillis: Long = HOLD_MILLIS,
    private val nudgeMillis: Long = NUDGE_MILLIS,
) : LiveCall {
    private val outgoing = Channel<String>(Channel.UNLIMITED)
    private val transcripts = TranscriptStream()
    @Volatile private var ended = false
    @Volatile private var closing = false

    private val gate = Mutex()
    private val open = mutableSetOf<String>()
    private val held = mutableListOf<Pair<String, String>>()
    @Volatile private var scope: CoroutineScope? = null
    private var holdTimer: Job? = null
    private var nudgeTimer: Job? = null

    override suspend fun run(onEvent: suspend (LiveCallEvent) -> Unit) {
        val tokens = codexTokens(auth)
        try {
            sidebands.connect(url, tokens, session) { socket ->
                coroutineScope {
                    val writer = launch { for (message in outgoing) socket.send(message) }
                    scope = this
                    try {
                        log.info { "Call $id attached" }
                        onEvent(LiveCallEvent.Ready)
                        socket.incoming.takeWhile { handle(it, onEvent) }.collect {}
                    } finally {
                        scope = null
                        writer.cancel(); holdTimer?.cancel(); nudgeTimer?.cancel()
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: LiveCallException) { throw e }
        catch (e: Exception) {
            log.warn(e) { "Call $id side channel failed" }
            throw LiveCallException(CALL_DROPPED, e)
        }
        if (!ended && !closing) throw LiveCallException(CALL_DROPPED)
    }

    /** Progress is commentary, which the model may mention. An answer is speakable, so it waits for a turn in progress to end: context appended mid-turn is not spoken afterwards. */
    override suspend fun reply(id: String, text: String, final: Boolean) {
        if (!final) return append(text) { delegationContext(id, it, COMMENTARY) }
        gate.withLock {
            val active = scope
            if (open.isEmpty() || active == null) deliver(id, text)
            else {
                log.info { "Call ${this.id} holds the answer to $id until the open turn ends" }
                held += id to text
                if (holdTimer == null) holdTimer = active.launch {
                    delay(holdMillis)
                    gate.withLock {
                        holdTimer = null
                        log.warn { "Call ${this@CodexLiveCall.id} still has a turn open after ${holdMillis}ms; delivering the answer anyway" }
                        flush()
                    }
                }
            }
        }
    }

    override suspend fun notify(text: String) = append(text) { sessionContext(it, SPEAKABLE) }

    private suspend fun append(text: String, message: (String) -> JsonObject) {
        for (chunk in contextAppendChunks(text)) outgoing.send(message(chunk).toString())
    }

    private suspend fun deliver(delegation: String, text: String) {
        append(text) { delegationContext(delegation, it, SPEAKABLE) }
        val active = scope ?: return
        nudgeTimer?.cancel()
        nudgeTimer = active.launch {
            delay(nudgeMillis)
            log.warn { "Call ${this@CodexLiveCall.id} did not speak the answer to $delegation within ${nudgeMillis}ms; nudging the model" }
            append(NUDGE) { sessionContext(it, SPEAKABLE) }
        }
    }

    private suspend fun flush() {
        val answers = held.toList()
        held.clear()
        for ((delegation, text) in answers) deliver(delegation, text)
    }

    private suspend fun turnStarted(turn: String, user: Boolean) = gate.withLock {
        open += turn
        if (!user) { nudgeTimer?.cancel(); nudgeTimer = null }
    }

    private suspend fun turnEnded(turn: String) = gate.withLock {
        open -= turn
        if (open.isEmpty() && held.isNotEmpty()) { holdTimer?.cancel(); holdTimer = null; flush() }
    }

    override suspend fun pause() = outgoing.send(control("input_audio.pause"))
    override suspend fun resume() = outgoing.send(control("input_audio.resume"))
    override suspend fun end() { closing = true; outgoing.send(control("session.close")) }

    /** Whether the call goes on. */
    private suspend fun handle(raw: String, onEvent: suspend (LiveCallEvent) -> Unit): Boolean {
        // Echoed microphone and reply audio is about a megabit a second and of no use here, so it's skipped unparsed.
        if (typeOf(raw) in AUDIO) return true
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return true
        val type = json.string("type")
        if (!type.endsWith(".delta") && !type.endsWith(".added")) log.info { "Call $id event $type" }
        when (type) {
            "session.started" -> onEvent(LiveCallEvent.Listening)
            "session.closed" -> { ended = true; return false }
            "turn.created" -> {
                val turn = json.getValue("turn").jsonObject
                val user = turn.string("role") == "user"
                transcripts.started(user, turn.string("id"))
                turnStarted(turn.string("id"), user)
                onEvent(if (user) LiveCallEvent.Listening else LiveCallEvent.Speaking)
            }
            "input_transcript.added", "output_transcript.added" -> {
                val piece = json["item"]?.jsonObject?.string("text").orEmpty()
                transcripts.added(type == "input_transcript.added", piece)?.let { onEvent(it) }
            }
            "turn.done" -> {
                val turn = json.getValue("turn").jsonObject
                val user = turn.string("role") == "user"
                turnEnded(turn.string("id"))
                onEvent(transcripts.done(user, turn.string("id"), turn.string("transcript")))
                if (!user) onEvent(LiveCallEvent.Listening)
            }
            "delegation.created" -> {
                val item = json.getValue("item").jsonObject
                val target = item.string("target")
                if (target.isEmpty() || target == "client") {
                    val task = item["content"]?.jsonArray?.joinToString("") { it.jsonObject.string("text") }.orEmpty()
                    onEvent(LiveCallEvent.Delegation(item.string("id"), task))
                }
            }
            "error" -> {
                val detail = (json["error"] as? JsonObject) ?: json
                if (recoverableRealtimeError(detail)) log.warn { "Realtime request rejected: $detail" }
                else { log.warn { "Realtime error: $detail" }; throw LiveCallException(realtimeErrorMessage(detail)) }
            }
        }
        return true
    }

    private companion object {
        const val CALL_DROPPED = "The call dropped."
        const val SPEAKABLE = "speakable"
        const val COMMENTARY = "commentary"
        const val HOLD_MILLIS = 8_000L
        const val NUDGE_MILLIS = 8_000L
        const val NUDGE = "The Agent's answer has arrived. Tell the user the result now."
        val AUDIO = setOf("session.input_audio.append", "session.output_audio.delta")

        /** The event type without parsing the frame, when it leads it. */
        fun typeOf(raw: String): String? {
            val at = raw.indexOf("\"type\":\"")
            if (at !in 0..64) return null
            val start = at + 8
            return raw.substring(start, raw.indexOf('"', start).takeIf { it > start } ?: return null)
        }

        fun control(type: String) = buildJsonObject { put("type", type) }.toString()

        fun delegationContext(id: String, text: String, channel: String) = buildJsonObject {
            put("type", "delegation.context.append")
            put("delegation_item_id", id)
            put("channel", channel)
            put("content", inputText(text))
        }

        fun sessionContext(text: String, channel: String) = buildJsonObject {
            put("type", "session.context.append")
            put("channel", channel)
            put("content", inputText(text))
        }

        fun inputText(text: String) = buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) }
    }
}

/** Each role's transcript so far: the `.added` events carry pieces, `turn.done` the final text. */
internal class TranscriptStream {
    private val turns = arrayOf("", "")
    private val texts = arrayOf(StringBuilder(), StringBuilder())

    fun started(user: Boolean, turn: String) { turns[index(user)] = turn }

    fun added(user: Boolean, piece: String): LiveCallEvent.Transcript? {
        if (piece.isEmpty()) return null
        val text = texts[index(user)].append(piece).toString().trim()
        return LiveCallEvent.Transcript(turns[index(user)], text, user, final = false).takeIf { text.isNotEmpty() }
    }

    fun done(user: Boolean, turn: String, transcript: String): LiveCallEvent.Transcript {
        texts[index(user)].clear()
        return LiveCallEvent.Transcript(turn, transcript.trim(), user)
    }

    private fun index(user: Boolean) = if (user) 0 else 1
}
