package kinuvo.harness.codex

import kinuvo.harness.core.voice.AgentProgress
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.webrtc.DataChannelEvent
import io.ktor.client.webrtc.JvmWebRtc
import io.ktor.client.webrtc.TrackEvent
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.client.webrtc.WebRtcDataChannel
import io.ktor.client.webrtc.WebRtcMedia
import io.ktor.client.webrtc.media.AudioFactory
import io.ktor.client.webrtc.media.JvmMediaDevices
import io.ktor.client.webrtc.media.getNative
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ExperimentalKtorApi
import kinuvo.harness.core.voice.AudioFormat
import kinuvo.harness.core.voice.SpeechToSpeech
import kinuvo.harness.core.voice.VoiceContext
import kinuvo.harness.core.voice.VoiceInput
import kinuvo.harness.core.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("codex.realtime")

/**
 * Speech-to-speech over the ChatGPT subscription: Codex's realtime "frameless" (V3) protocol on a WebRTC call this
 * Server holds itself. Media flows over the call; events and delegations over its `oai-events` data channel.
 * Undocumented backend, mirrored from the Codex CLI: an invalid [voice] still creates the call but it never activates.
 */
@OptIn(ExperimentalKtorApi::class)
class CodexRealtime(
    private val auth: CodexAuth,
    private val voice: String = "cove",
    private val model: String = "gpt-live-1-codex",
    private val http: HttpClient = HttpClient(codexEngine()),
) : SpeechToSpeech {
    override val input = AudioFormat.Pcm16(24_000)
    override val output = AudioFormat.Pcm16(48_000)

    override suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
        try { converseAvailable(context, input, output, askAgent) }
        catch (e: LinkageError) { throw RealtimeCallException(UNSUPPORTED_PLATFORM, e) }
    }

    private suspend fun converseAvailable(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String) {
        val audio = PcmAudio()
        val rtc = WebRtcClient(JvmWebRtc) {
            mediaTrackFactory = JvmMediaDevices(audioFactory = audio)
            defaultConnectionConfig = { bundlePolicy = WebRtc.BundlePolicy.MAX_BUNDLE }
        }
        try {
            val peer = rtc.createPeerConnection()
            // Closed before the factory goes, however the call ends, or webrtc's threads outlive what they use.
            try { coroutineScope {
                val heard = Channel<ByteArray>(Channel.UNLIMITED)
                launch {
                    val track = peer.trackEvents.filterIsInstance<TrackEvent.Add>().first().track as WebRtcMedia.AudioTrack
                    track.getNative().addSink { data, _, _, channels, frames -> heard.trySend(mono(data, channels, frames)) }
                }
                launch { peer.state.collect { log.info { "Call connection $it" } } }
                launch {
                    peer.state.first { it == WebRtc.ConnectionState.FAILED || it == WebRtc.ConnectionState.CLOSED }
                    log.warn { "Call ${peer.state.value.name.lowercase()}" }
                    throw RealtimeCallException(CALL_DROPPED)
                }
                peer.addTrack(rtc.createAudioTrack {})
                val events = peer.createDataChannel("oai-events")
                val messages = Channel<JsonObject>(Channel.UNLIMITED)
                launch { for (message in events) messages.send(Json.parseToJsonElement(message.textOrNull() ?: message.binaryOrThrow().decodeToString()).jsonObject) }
                launch { peer.dataChannelEvents.first { it is DataChannelEvent.Closed }; log.warn { "Event channel closed" }; throw RealtimeCallException(CALL_DROPPED) }
                peer.setLocalDescription(peer.createOffer())
                try { withTimeout(10_000) { peer.awaitIceGatheringComplete() } }
                catch (e: TimeoutCancellationException) { throw RealtimeCallException(UNREACHABLE, e) }
                peer.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.ANSWER, call(peer.localDescription!!.sdp, context)))

                val reply = ReplyAudio()
                val transcripts = Transcripts()
                var paused = false
                val rate = this@CodexRealtime.input.sampleRate
                val chunk = ByteArray(rate / 100 * 2)
                var filled = 0
                loop@ while (true) {
                    select {
                        input.onReceiveCatching { result ->
                            when (val next = result.getOrNull()) {
                                null -> return@onReceiveCatching false
                                is VoiceInput.Audio -> if (!paused) {
                                    var offset = 0
                                    while (offset < next.pcm.size) {
                                        val n = minOf(chunk.size - filled, next.pcm.size - offset)
                                        next.pcm.copyInto(chunk, filled, offset, offset + n)
                                        filled += n; offset += n
                                        if (filled == chunk.size) { audio.source.pushAudio(chunk, 16, rate, 1, chunk.size / 2); filled = 0 }
                                    }
                                }
                                is VoiceInput.Notify -> {
                                    log.info { "Sending best-effort out-of-delegation realtime text" }
                                    events.send(notificationItem(next.text).toString())
                                    events.send(buildJsonObject { put("type", "response.create") }.toString())
                                }
                                VoiceInput.Pause -> paused = true
                                VoiceInput.Resume -> paused = false
                                is VoiceInput.Interrupt -> reply.interrupt(next.item).forEach { output(it) }
                                is VoiceInput.Played -> {}
                            }
                            true
                        }
                        heard.onReceive { pcm -> reply.heard(pcm).forEach { output(it) }; true }
                        messages.onReceive { json -> handle(json, events, output, askAgent, this@coroutineScope, reply, transcripts) }
                    }.let { if (!it) break@loop }
                }
                coroutineContext.cancelChildren()
            } } finally { peer.close() }
        } finally {
            rtc.close()
            audio.close()
        }
    }

    private suspend fun handle(
        json: JsonObject, channel: WebRtcDataChannel, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String,
        scope: CoroutineScope, reply: ReplyAudio, transcripts: Transcripts,
    ): Boolean {
        json.string("type").takeUnless { it.endsWith(".delta") || it.endsWith(".added") }?.let { log.info { "Call event $it" } }
        when (json.string("type")) {
            "session.started" -> { output(VoiceOutput.Ready); output(VoiceOutput.Listening) }
            "session.closed" -> return false
            "turn.created" -> {
                val turn = json.getValue("turn").jsonObject
                val user = turn.string("role") == "user"
                transcripts.started(user, turn.string("id"))
                if (user) reply.interrupt().forEach { output(it) }
                else reply.started(turn.string("id")).forEach { output(it) }
            }
            "input_transcript.added", "output_transcript.added" -> {
                val text = json["item"]?.jsonObject?.string("text").orEmpty()
                transcripts.added(json.string("type") == "input_transcript.added", text)?.let { output(it) }
            }
            "turn.done" -> {
                val turn = json.getValue("turn").jsonObject
                val user = turn.string("role") == "user"
                output(transcripts.done(user, turn.string("id"), turn.string("transcript")))
                // The turn's audio is still streaming in when its text is done, so it keeps playing under the same item.
                if (!user) { reply.textDone(); output(VoiceOutput.Done(turn.string("id"))); output(VoiceOutput.Listening) }
            }
            "delegation.created" -> {
                val item = json.getValue("item").jsonObject
                if (item.string("target") != "client") return true
                val task = item.getValue("content").jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
                output(VoiceOutput.Thinking)
                scope.launch {
                    suspend fun append(value: String) {
                        for (text in contextAppendChunks(value)) channel.send(buildJsonObject {
                            put("type", "delegation.context.append")
                            put("delegation_item_id", item.string("id"))
                            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) })
                        }.toString())
                    }
                    val answer = try { askAgent(task) { append(it.voiceText()) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { "The task failed: ${e.message}" }
                    append(answer)
                }
            }
            "error" -> {
                val detail = json["error"] ?: json
                if (recoverableRealtimeError(detail as? JsonObject ?: json)) log.warn { "Realtime request rejected: $detail" }
                else { log.warn { "Realtime error: $detail" }; throw RealtimeCallException(realtimeErrorMessage(detail as? JsonObject ?: json)) }
            }
        }
        return true
    }

    /** Creates the call and returns the answer SDP. */
    private suspend fun call(sdp: String, context: VoiceContext): String {
        val body = buildJsonObject {
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
        val session = Uuid.random().toString()
        suspend fun post(forceRefresh: Boolean): HttpResponse {
            val tokens = try { auth.tokens(forceRefresh) } catch (e: IllegalStateException) {
                log.warn(e) { "Realtime call has no ChatGPT login" }
                throw RealtimeCallException(SIGN_IN, e)
            }
            return try { http.post(CALLS_URL) {
                header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
                header("chatgpt-account-id", tokens.accountId)
                header("OpenAI-Alpha", "quicksilver=v2")
                header("originator", "kinuvo")
                header("session-id", session)
                header("x-session-id", session)
                contentType(ContentType.Application.Json)
                setBody(body)
            } } catch (e: IOException) {
                log.warn(e) { "Realtime call unreachable" }
                throw RealtimeCallException(UNREACHABLE, e)
            }
        }
        var response = post(forceRefresh = false)
        if (response.status == HttpStatusCode.Unauthorized) response = post(forceRefresh = true)
        log.info { "Call created: ${response.status}" }
        if (!response.status.isSuccess()) {
            val detail = response.bodyAsText()
            log.warn { "Realtime call failed (${response.status}): ${detail.take(500)}" }
            throw RealtimeCallException(callFailure(response.status.value, detail))
        }
        return response.bodyAsText()
    }

    /** Microphone and speaker are the Device's, so the call's audio is pushed in and read out as PCM. */
    private class PcmAudio : AudioFactory {
        override val audioModule = HeadlessAudioDeviceModule()
        // Loads the native library, so it comes before the source.
        override val peerConnectionFactory = PeerConnectionFactory(audioModule)
        val source = CustomAudioSource()
        override fun createAudioSource(constraints: WebRtcMedia.AudioTrackConstraints): AudioTrackSource = source
        override fun close() { source.dispose(); peerConnectionFactory.dispose() }
    }

    companion object {
        fun available(): Boolean = realtimeAvailable { PcmAudio().close() }

        private const val CALL_DROPPED = "The call dropped."
        private const val CALLS_URL = "${CODEX_BASE_URL}realtime/calls?intent=quicksilver&architecture=avas"
    }
}

/** Each role's transcript so far: the `.added` events carry pieces, `turn.done` the final text. */
internal class Transcripts {
    private val turns = arrayOf("", "")
    private val texts = arrayOf(StringBuilder(), StringBuilder())

    fun started(user: Boolean, turn: String) { turns[index(user)] = turn }

    fun added(user: Boolean, piece: String): VoiceOutput.Transcript? {
        if (piece.isEmpty()) return null
        val text = texts[index(user)].append(piece).toString().trim()
        return VoiceOutput.Transcript(turns[index(user)], text, user, final = false).takeIf { text.isNotEmpty() }
    }

    fun done(user: Boolean, turn: String, transcript: String): VoiceOutput.Transcript {
        texts[index(user)].clear()
        return VoiceOutput.Transcript(turn, transcript.trim(), user)
    }

    private fun index(user: Boolean) = if (user) 0 else 1
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()

private fun mono(data: ByteArray, channels: Int, frames: Int): ByteArray {
    if (channels == 1) return data.copyOf(frames * 2)
    val out = ByteArray(frames * 2)
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) {
            val at = (i * channels + c) * 2
            sum += (data[at].toInt() and 0xff) or (data[at + 1].toInt() shl 8)
        }
        val v = sum / channels
        out[i * 2] = v.toByte(); out[i * 2 + 1] = (v shr 8).toByte()
    }
    return out
}

// Match Codex's frameless context append limit, preserving code points and all text.
internal fun contextAppendChunks(text: String): List<String> {
    val bytes = text.encodeToByteArray()
    if (bytes.size <= 500) return listOf(text)
    return buildList {
        var start = 0
        while (start < bytes.size) {
            var end = minOf(start + 500, bytes.size)
            while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end--
            add(bytes.decodeToString(start, end))
            start = end
        }
    }
}

/** A call failure worded for the Device; the raw detail goes to the Server log. */
internal class RealtimeCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal const val UNREACHABLE = "Couldn't reach ChatGPT. Check the Server's connection."
internal const val SIGN_IN = "Sign in to ChatGPT again in Settings → Model providers."

/** What a Device sees when creating the call fails with [status] and [body]. */
internal fun callFailure(status: Int, body: String, now: Instant = Clock.System.now()): String {
    val error = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
    return when (status) {
        401, 403 -> SIGN_IN
        429 -> if (error?.string("type") == "usage_limit_reached") {
            val seconds = error["resets_in_seconds"]?.jsonPrimitive?.longOrNull
                ?: error["resets_at"]?.jsonPrimitive?.longOrNull?.let { it - now.epochSeconds }
            "ChatGPT's usage limit is reached." + (seconds?.takeIf { it > 0 }?.let { " It resets in ${duration(it)}." } ?: "")
        } else "ChatGPT is busy. Try again in a moment."
        else -> "Couldn't start the call (HTTP $status)."
    }
}

private fun duration(seconds: Long): String {
    val minutes = maxOf(1, (seconds + 30) / 60)
    fun unit(n: Long, name: String) = "$n $name" + if (n == 1L) "" else "s"
    return when {
        minutes < 60 -> unit(minutes, "minute")
        minutes % 60 == 0L -> unit(minutes / 60, "hour")
        else -> "${unit(minutes / 60, "hour")} ${unit(minutes % 60, "minute")}"
    }
}

internal fun realtimeErrorMessage(error: JsonObject): String = error.string("message").ifBlank { "The call ended unexpectedly." }

// Request validation rejects one event; session/auth failures and unknown errors remain fatal.
internal fun recoverableRealtimeError(error: JsonObject): Boolean =
    error.string("type") == "invalid_request_error" && error.string("code") !in setOf(
        "session_expired", "session_closed", "session_not_found", "invalid_session",
        "invalid_api_key", "invalid_authentication", "authentication_error", "token_expired",
    )

internal const val UNSUPPORTED_PLATFORM = "Live voice isn't available on this Server's platform."

internal fun realtimeAvailable(
    os: String = System.getProperty("os.name"),
    arch: String = System.getProperty("os.arch"),
    probe: () -> Unit,
): Boolean {
    if (os != "Mac OS X" || arch !in setOf("aarch64", "arm64")) return false
    return try { probe(); true }
    catch (e: LinkageError) { log.warn(e) { UNSUPPORTED_PLATFORM }; false }
    catch (e: Exception) { log.warn(e) { UNSUPPORTED_PLATFORM }; false }
}

internal fun AgentProgress.voiceText(): String = when (this) {
    is AgentProgress.ApprovalRequested -> "Waiting for the user's approval on screen to run ${tool.take(80)}: ${summary.take(240)}. Tell the user to review it in the app. Approvals must only be answered on screen, never by voice."
    is AgentProgress.ApprovalResolved -> when (allowed) {
        true -> "The user allowed the action. The Agent is continuing."
        false -> "The user denied the action."
        null -> "The approval request is no longer pending. Its decision is unavailable; do not assume it was allowed."
    }
    is AgentProgress.Question -> "The Agent asks: ${text.take(600)}. Tell the user to answer in the app."
    is AgentProgress.Text -> "Agent progress (not the final result): ${text.take(2000)}"
}

internal fun notificationItem(text: String) = buildJsonObject {
    put("type", "conversation.item.create")
    put("item", buildJsonObject {
        put("type", "message")
        put("role", "developer")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) })
    })
}
