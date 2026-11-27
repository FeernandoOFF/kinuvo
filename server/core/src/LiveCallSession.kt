package kinuvo.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.VoiceTurn
import kinuvo.protocol.voice.LiveCallPhase
import kinuvo.protocol.voice.LiveCallUpdate
import kinuvo.protocol.voice.LivePeerState
import kinuvo.server.plugin.AgentProgress
import kinuvo.server.plugin.LiveCall
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.LiveCallException
import kinuvo.server.plugin.ReplyKind
import kinuvo.server.plugin.voiceText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("kinuvo.voice")

/**
 * A live call the Device holds: the Device owns the media connection, this controls the provider's side of it. It starts
 * once the Device reports having applied the answer, runs the Agent's delegated work and keeps the transcript for the Session.
 */
internal class LiveCallSession(
    private val call: LiveCall,
    private val scope: CoroutineScope,
    private val emit: (LiveCallUpdate) -> Boolean,
    private val askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String,
    private val record: suspend (SessionEntry.VoiceCall) -> Unit = {},
    private val keepAwake: () -> AutoCloseable = { AutoCloseable {} },
    private val attachTimeoutMillis: Long = 15_000,
    private val endTimeoutMillis: Long = 5_000,
    private val heartbeatMillis: Long = 20_000,
) {
    val id = Uuid.random().toString()
    private val attached = CompletableDeferred<Unit>()
    private var job: Job? = null
    @Volatile private var ended = false
    @Volatile private var failure: String? = null
    @Volatile private var paused = false
    val active get() = !ended && job?.isActive == true

    fun start() {
        log.info { "Live call $id starting" }
        job = scope.launch {
            val awake = keepAwake()
            val transcript = Transcript(id, record)
            var error: String? = null
            try {
                try { withTimeout(attachTimeoutMillis) { attached.await() } }
                catch (e: TimeoutCancellationException) { throw LiveCallException("The call never connected.") }
                coroutineScope {
                    try { call.run { handle(it, transcript, this@coroutineScope) } }
                    finally { currentCoroutineContext().cancelChildren() }
                }
                error = failure
            } catch (e: CancellationException) { throw e }
            catch (e: LiveCallException) { log.warn { "Live call $id failed: ${e.message}" }; error = failure ?: e.message }
            catch (e: Exception) { log.warn(e) { "Live call $id failed" }; error = failure ?: e.message?.take(300) ?: "The call failed. Please try again." }
            finally {
                try { awake.close() } finally {
                    try {
                        withContext(NonCancellable) { transcript.save() }
                    } catch (e: Exception) {
                        log.warn(e) { "Live call $id transcript could not be saved" }
                        if (error == null) error = "The call transcript couldn't be saved."
                    } finally {
                        log.info { "Live call $id ended" }
                        ended = true
                        emit(LiveCallUpdate.Ended(id, error))
                    }
                }
            }
        }
    }

    private suspend fun handle(event: LiveCallEvent, transcript: Transcript, tasks: CoroutineScope) {
        when (event) {
            LiveCallEvent.Ready, LiveCallEvent.Listening -> phase(LiveCallPhase.Listening)
            LiveCallEvent.Speaking -> phase(LiveCallPhase.Speaking)
            is LiveCallEvent.Transcript -> {
                if (event.final && event.text.isNotBlank()) transcript.add(VoiceTurn(event.user, event.text))
                send(LiveCallUpdate.Transcript(id, event.item, event.text, event.user, event.final))
            }
            is LiveCallEvent.Delegation -> {
                phase(LiveCallPhase.Thinking)
                try { transcript.save() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.warn(e) { "Live call $id transcript could not be saved yet" } }
                tasks.launch { delegate(event) }
            }
        }
    }

    /** Runs the Agent on [event]'s task. Its run belongs to the Server, so it carries on if the call ends; only this wait stops. */
    private suspend fun delegate(event: LiveCallEvent.Delegation) = coroutineScope {
        val reported = Reported()
        val heartbeat = launch {
            while (true) {
                delay(heartbeatMillis)
                if (!reported.value) reply(event.id, "The Agent is still working on: ${event.task.take(80)}. If the user has been waiting, briefly tell them it is still in progress; don't invent details.", ReplyKind.Progress)
                reported.value = false
            }
        }
        val answer = try { askAgent(event.task) { reported.value = true; reply(event.id, it.voiceText(), if (it is AgentProgress.ApprovalRequested || it is AgentProgress.Question) ReplyKind.Prompt else ReplyKind.Progress) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { "The task failed: ${e.message}" }
        heartbeat.cancel()
        reply(event.id, answer, ReplyKind.Final)
    }

    private suspend fun reply(delegation: String, text: String, kind: ReplyKind) {
        if (!active) return
        try { call.reply(delegation, text, kind) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.warn(e) { "Live call $id could not receive a reply" } }
    }

    private fun phase(phase: LiveCallPhase) = send(LiveCallUpdate.Phase(id, if (paused && phase == LiveCallPhase.Listening) LiveCallPhase.Paused else phase))

    private fun send(update: LiveCallUpdate) {
        if (!emit(update)) { failure = "Device disconnected"; job?.cancel() }
    }

    /** The Device's connection state: the call attaches once it has applied the answer, and ends when the connection does. */
    suspend fun peer(state: LivePeerState) {
        if (!active) return
        when (state) {
            LivePeerState.Connecting, LivePeerState.Connected -> attached.complete(Unit)
            LivePeerState.Failed -> { failure = "The call connection failed."; end() }
            LivePeerState.Closed -> end()
        }
    }

    suspend fun notify(text: String) = control { call.notify(text) }

    suspend fun pause() = control { paused = true; call.pause(); phase(LiveCallPhase.Paused) }

    suspend fun resume() = control { paused = false; call.resume(); phase(LiveCallPhase.Listening) }

    /** Asks the provider to close the call, cancelling it if it hasn't within a few seconds. */
    suspend fun end() {
        if (!active) return
        val running = job ?: return
        if (!attached.isCompleted) { running.cancel(); return }
        control { call.end() }
        scope.launch {
            try { withTimeout(endTimeoutMillis) { running.join() } }
            catch (e: TimeoutCancellationException) { running.cancel() }
        }
    }

    fun close() { job?.cancel() }

    private suspend fun control(block: suspend () -> Unit) {
        if (!active) return
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.warn(e) { "Live call $id could not be controlled" } }
    }
}

private class Reported { @Volatile var value = false }

/** The call's turns, saved to the Session in parts: each delegation saves what was said before it, so the Session reads in the order things happened. */
private class Transcript(private val callId: String, private val record: suspend (SessionEntry.VoiceCall) -> Unit) {
    private val turns = mutableListOf<VoiceTurn>()
    private val clock = TimeSource.Monotonic.markNow()
    private var from = Clock.System.now().toEpochMilliseconds()
    private var offset = 0L
    private var part = 0

    fun add(turn: VoiceTurn) { turns += turn }

    suspend fun save() {
        if (turns.isEmpty()) return
        val now = clock.elapsedNow().inWholeMilliseconds
        record(SessionEntry.VoiceCall(if (part == 0) callId else "$callId-$part", turns.toList(), from, now - offset))
        turns.clear()
        from += now - offset
        offset = now
        part++
    }
}
