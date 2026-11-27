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
            val startedAt = Clock.System.now().toEpochMilliseconds()
            val elapsed = TimeSource.Monotonic.markNow()
            val turns = mutableListOf<VoiceTurn>()
            var error: String? = null
            try {
                try { withTimeout(attachTimeoutMillis) { attached.await() } }
                catch (e: TimeoutCancellationException) { throw LiveCallException("The call never connected.") }
                coroutineScope {
                    try { call.run { handle(it, turns, this@coroutineScope) } }
                    finally { currentCoroutineContext().cancelChildren() }
                }
                error = failure
            } catch (e: CancellationException) { throw e }
            catch (e: LiveCallException) { log.warn { "Live call $id failed: ${e.message}" }; error = failure ?: e.message }
            catch (e: Exception) { log.warn(e) { "Live call $id failed" }; error = failure ?: e.message?.take(300) ?: "The call failed. Please try again." }
            finally {
                try { awake.close() } finally {
                    try {
                        if (turns.isNotEmpty()) withContext(NonCancellable) { record(SessionEntry.VoiceCall(id, turns.toList(), startedAt, elapsed.elapsedNow().inWholeMilliseconds)) }
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

    private suspend fun handle(event: LiveCallEvent, turns: MutableList<VoiceTurn>, tasks: CoroutineScope) {
        when (event) {
            LiveCallEvent.Ready, LiveCallEvent.Listening -> phase(LiveCallPhase.Listening)
            LiveCallEvent.Speaking -> phase(LiveCallPhase.Speaking)
            is LiveCallEvent.Transcript -> {
                if (event.final && event.text.isNotBlank()) turns += VoiceTurn(event.user, event.text)
                send(LiveCallUpdate.Transcript(id, event.item, event.text, event.user, event.final))
            }
            is LiveCallEvent.Delegation -> {
                phase(LiveCallPhase.Thinking)
                tasks.launch {
                    val answer = try { askAgent(event.task) { reply(event.id, it.voiceText()) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { "The task failed: ${e.message}" }
                    reply(event.id, answer)
                }
            }
        }
    }

    private suspend fun reply(delegation: String, text: String) {
        try { call.reply(delegation, text) }
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
