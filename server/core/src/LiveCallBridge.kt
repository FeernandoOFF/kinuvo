package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.voice.*
import kinuvo.server.execution.*
import kinuvo.server.plugin.AgentProgress
import kinuvo.server.plugin.LiveCallException
import kinuvo.server.plugin.VoiceContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Starts a live call for a connection: the call talks, and hands the Agent's work to its Session. */
internal class LiveCallBridge(
    private val harness: AgentRuntime,
    private val voice: VoiceService,
    private val executions: ExecutionRegistry,
    private val execution: ExecutionService,
    private val admin: Admin,
    private val dispatch: AgentExecution,
    private val runs: Runs,
    private val keepAwake: () -> AutoCloseable,
) {
    /** The provider creates the call from the Device's offer, and the session runs once the Device attaches. */
    context(_: Raise<VoiceError>, _: Raise<AgentError>)
    suspend fun start(p: LiveCallParams, scope: CoroutineScope, emit: (LiveCallUpdate) -> Boolean, attach: (LiveCallSession) -> Unit): LiveCallAnswer {
        val provider = voice.providers.liveCalls ?: raise(VoiceError.NoLiveVoice)
        require(p.offerSdp.isNotBlank() && p.offerSdp.length <= MAX_SDP_CHARS) { "Invalid call offer" }
        val (registration, context) = prepare(p.agentId, p.sessionId)
        val started = try { withTimeout(START_TIMEOUT_MILLIS) { provider.start(context, p.offerSdp) } }
        catch (e: LiveCallException) { raise(VoiceError.CallFailed(e.message ?: "Couldn't start the call.")) }
        catch (e: TimeoutCancellationException) { raise(VoiceError.CallFailed("Couldn't start the call. Check the Server's connection.")) }
        val session = LiveCallSession(started.call, scope, emit, askAgent(p.agentId, p.sessionId, registration),
            record = { entry -> harness.store.append(p.agentId, p.sessionId, entry) }, keepAwake = keepAwake)
        attach(session)
        session.start()
        return LiveCallAnswer(session.id, started.answerSdp)
    }

    context(_: Raise<AgentError>)
    private suspend fun prepare(agentId: AgentId, sessionId: SessionId): Pair<AgentRegistration?, VoiceContext> {
        val registration = executions.registration(agentId)
        val name = registration?.let { execution.externalInfo(it).name } ?: admin.agent(agentId).name
        dispatch.snapshot(agentId, sessionId)
        return registration to voiceContext(name, harness.store.entries(agentId, sessionId))
    }

    /** Runs the Session's Agent on [task], reporting its progress, and returns its reply. */
    private fun askAgent(agentId: AgentId, sessionId: SessionId, registration: AgentRegistration?): suspend (String, suspend (AgentProgress) -> Unit) -> String = ask@{ task, progress ->
        if (registration != null) {
            try {
                return@ask withTimeout(300_000) {
                    val before = harness.store.entries(agentId, sessionId).size
                    val updates = VoiceAgentProgress(dispatch.snapshot(agentId, sessionId))
                    dispatch.send(SendParams(agentId = agentId, sessionId = sessionId, text = task, voice = true))
                    var snapshot = dispatch.snapshot(agentId, sessionId)
                    updates.update(snapshot).forEach { progress(it) }
                    while (snapshot.phase == ExecutionPhase.Running || snapshot.phase == ExecutionPhase.RequiresAction) {
                        delay(100)
                        snapshot = dispatch.snapshot(agentId, sessionId)
                        updates.update(snapshot).forEach { progress(it) }
                    }
                    if (snapshot.phase == ExecutionPhase.Lost) "The Agent could not finish the request."
                    else harness.store.entries(agentId, sessionId).drop(before)
                        .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text ?: "The Agent finished without a reply."
                }
            } catch (e: TimeoutCancellationException) { return@ask "The Agent took too long to reply." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return@ask "The Agent could not finish the request." }
        }
        runs.join(sessionId)
        var reply = "The Agent finished without a reply."
        var failure: String? = null
        val updates = NativeVoiceProgress()
        throwing<ServerError, SendResult> {
            dispatch.send(SendParams(agentId = agentId, sessionId = sessionId, text = task, voice = true), null) {
                updates.update(it).forEach { update -> progress(update) }
                when (it) {
                    is AgentEvent.TextMessageEnd -> reply = it.text
                    is AgentEvent.RunError -> failure = "The Agent could not finish the request"
                    else -> Unit
                }
            }
        }
        runs.join(sessionId)
        failure?.let { error(it) }
        reply
    }

    private companion object {
        const val MAX_SDP_CHARS = 64_000
        const val START_TIMEOUT_MILLIS = 20_000L
    }
}
