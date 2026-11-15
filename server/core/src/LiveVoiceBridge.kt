package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.voice.*
import kinuvo.server.execution.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Runs a live voice call for a connection: the call talks, and hands the Agent's work to its Session. */
internal class LiveVoiceBridge(
    private val harness: HarnessRuntime,
    private val voice: VoiceService,
    private val executions: ExecutionRegistry,
    private val execution: ExecutionService,
    private val admin: Admin,
    private val dispatch: AgentExecution,
    private val runs: Runs,
    private val keepAwake: () -> AutoCloseable,
) {
    context(_: Raise<VoiceError>, _: Raise<AgentError>)
    suspend fun start(p: LiveVoiceParams, scope: CoroutineScope, output: (LiveVoiceOutput) -> Delivery, attach: (LiveVoiceSession) -> Unit): LiveVoiceCall {
        val provider = voice.providers.speechToSpeech ?: raise(VoiceError.NoLiveVoice)
        val registration = executions.registration(p.agentId)
        val name = registration?.let { execution.externalInfo(it).name } ?: admin.agent(p.agentId).name
        dispatch.snapshot(p.agentId, p.sessionId)
        return LiveVoiceSession(provider, scope, voiceContext(name, harness.store.entries(p.agentId, p.sessionId)),
            output,
            askAgent = { task, progress ->
                if (registration != null) {
                    try {
                        return@LiveVoiceSession withTimeout(300_000) {
                            val before = harness.store.entries(p.agentId, p.sessionId).size
                            val updates = VoiceAgentProgress(dispatch.snapshot(p.agentId, p.sessionId))
                            dispatch.send(SendParams(agentId = p.agentId, sessionId = p.sessionId, text = task, voice = true))
                            var snapshot = dispatch.snapshot(p.agentId, p.sessionId)
                            updates.update(snapshot).forEach { progress(it) }
                            while (snapshot.phase == ExecutionPhase.Running || snapshot.phase == ExecutionPhase.RequiresAction) {
                                delay(100)
                                snapshot = dispatch.snapshot(p.agentId, p.sessionId)
                                updates.update(snapshot).forEach { progress(it) }
                            }
                            if (snapshot.phase == ExecutionPhase.Lost) "The Agent could not finish the request."
                            else harness.store.entries(p.agentId, p.sessionId).drop(before)
                                .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text ?: "The Agent finished without a reply."
                        }
                    } catch (e: TimeoutCancellationException) { return@LiveVoiceSession "The Agent took too long to reply." }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { return@LiveVoiceSession "The Agent could not finish the request." }
                }
                runs.join(p.sessionId)
                var reply = "The Agent finished without a reply."
                var failure: String? = null
                val updates = NativeVoiceProgress()
                throwing<ServerError, SendResult> {
                    dispatch.send(SendParams(agentId = p.agentId, sessionId = p.sessionId, text = task, voice = true), null) {
                        updates.update(it).forEach { update -> progress(update) }
                        when (it) {
                            is AgentEvent.TextMessageEnd -> reply = it.text
                            is AgentEvent.RunError -> failure = "The Agent could not finish the request"
                            else -> Unit
                        }
                    }
                }
                runs.join(p.sessionId)
                failure?.let { error(it) }
                reply
            }, record = { entry -> harness.store.append(p.agentId, p.sessionId, entry) }, keepAwake = keepAwake).also(attach).start()
    }
}
