package kinuvo.app.session

import kinuvo.app.connection.pendingLink
import kinuvo.app.navigation.Route
import kinuvo.client.userMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

data class ActiveCall(
    val agentId: String,
    val sessionId: String,
    val agentName: String = agentId,
    val voice: VoiceChatState = VoiceChatState(),
    val startedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val durationSeconds: Long = 0,
)

/** Owns live audio independently of any destination's ViewModel lifetime. */
class LiveCalls(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    val state: StateFlow<ActiveCall?>
        field = MutableStateFlow<ActiveCall?>(null)
    private var chat: VoiceChat? = null
    private var observation: Job? = null
    private var timer: Job? = null
    private var starting: Job? = null

    fun start(agent: String, session: String, voice: VoiceChat, name: String = agent) {
        if (state.value?.let { it.agentId == agent && it.sessionId == session && it.voice.phase != VoicePhase.Off } == true) return
        end()
        chat = voice
        state.value = ActiveCall(agent, session, name)
        voice.start()
        state.update { it?.copy(voice = voice.state.value) }
        observation = scope.launch {
            voice.state.collect { value ->
                state.update { it?.copy(voice = value) }
                if (value.phase == VoicePhase.Off) {
                    timer?.cancel()
                    if (value.error == null) state.value = null
                }
            }
        }
        timer = scope.launch {
            while (isActive) {
                delay(1000)
                state.update { it?.copy(durationSeconds = (Clock.System.now().toEpochMilliseconds() - it.startedAt) / 1000) }
            }
        }
    }

    fun mute(muted: Boolean) { if (muted) chat?.pause() else chat?.resume() }
    fun speaker(on: Boolean) { chat?.speaker(on) }
    fun end() {
        starting?.cancel(); starting = null
        observation?.cancel(); observation = null
        timer?.cancel(); timer = null
        chat?.end(); chat = null
        state.value = null
    }
    fun openSession() { state.value?.let { pendingLink.value = Route.Chat(it.agentId, it.sessionId) } }

    fun attach(agent: String, session: String?, screenScope: CoroutineScope, resolve: suspend () -> Pair<String, String>?, name: suspend (String) -> String = { it }, create: (String, String) -> VoiceChat): VoiceChat = object : VoiceChat {
        private val observed = MutableStateFlow(VoiceChatState())
        override val state: StateFlow<VoiceChatState> get() = observed
        private var joined = session
        private var joinedAgent = agent
        init {
            screenScope.launch {
                this@LiveCalls.state.collect { call ->
                    observed.value = call?.takeIf { it.agentId == joinedAgent && it.sessionId == joined }?.voice ?: VoiceChatState()
                }
            }
        }
        override fun start() {
            observed.value = VoiceChatState(phase = VoicePhase.Starting, live = true)
            starting?.cancel()
            starting = scope.launch {
                try {
                    val (agentId, id) = resolve() ?: error("Pick an Agent first")
                    joinedAgent = agentId
                    joined = id
                    val displayName = name(agentId)
                    starting = null
                    this@LiveCalls.start(agentId, id, create(agentId, id), displayName)
                    observed.value = this@LiveCalls.state.value?.voice ?: VoiceChatState()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { observed.value = VoiceChatState(error = userMessage(e.message) ?: "Couldn't start the call", live = true) }
            }
        }
        override fun end() {
            if (matches()) this@LiveCalls.end()
            else { starting?.cancel(); starting = null; observed.value = VoiceChatState() }
        }
        override fun pause() { if (matches()) mute(true) }
        override fun resume() { if (matches()) mute(false) }
        override fun speaker(on: Boolean) { if (matches()) this@LiveCalls.speaker(on) }
        override fun hold() {}
        override fun letGo() {}
        override fun background() {}
        override fun foreground() {}
        private fun matches() = this@LiveCalls.state.value?.let { it.agentId == joinedAgent && it.sessionId == joined } == true
    }
}
