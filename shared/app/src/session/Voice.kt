package kinuvo.app.session

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Speech to text and text to speech, on-device for now; a Server-side engine would stream audio instead. The
 * microphone can stay open while speaking. Callbacks arrive on the main thread.
 */
interface SpeechEngine {
    /** Asks for microphone and speech recognition access; [completion] gets why it's denied, or null. */
    fun authorize(completion: (String?) -> Unit)
    /** Transcribes the microphone, replacing any earlier listen; with [untilPause], [onFinal] fires once the user pauses. */
    fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    /** Queues [text] after anything still being spoken; [onDone] fires once it's spoken or stopped. */
    fun speak(text: String, onDone: () -> Unit)
    fun stopSpeaking()
    /** Stops listening and speaking and releases the audio. */
    fun stop()
}

enum class VoicePhase { Off, Starting, Listening, Thinking, Speaking }

/**
 * [heard] is what the user is saying. [spoken] holds the reply's messages being read aloud, by item ID, with how far
 * into their text speech has reached. [paused] stops listening until resumed; [speaker] reads replies aloud.
 */
data class VoiceChatState(
    val phase: VoicePhase = VoicePhase.Off,
    val heard: String = "",
    val spoken: Map<String, Int> = emptyMap(),
    val paused: Boolean = false,
    val speaker: Boolean = true,
    val error: String? = null,
) {
    val active get() = phase != VoicePhase.Off || error != null
}

/**
 * A voice conversation in the open Session. [SpeechVoiceChat] turns speech into messages and replies into speech; a
 * speech-to-speech model would be another implementation behind the same controls.
 */
interface VoiceChat {
    val state: StateFlow<VoiceChatState>
    fun start()
    fun end()
    /** Cuts any reply short and stops listening until [resume]. */
    fun pause()
    fun resume()
    fun speaker(on: Boolean)
}

class VoiceViewModel(engine: SpeechEngine, session: SessionViewModel) : ViewModel() {
    val chat: VoiceChat = SpeechVoiceChat(engine, session, viewModelScope)

    override fun onCleared() = chat.end()
}

/**
 * Full duplex over a [SpeechEngine]: it keeps listening while the reply streams and is spoken, and the user talking
 * over it cuts it short and starts their next turn.
 */
class SpeechVoiceChat(private val speech: SpeechEngine, private val session: SessionViewModel, private val scope: CoroutineScope) : VoiceChat {
    override val state: StateFlow<VoiceChatState>
        field = MutableStateFlow(VoiceChatState())
    private var reply: Job? = null
    /** Words of the reply spoken so far, to tell the user talking from the reply's echo. */
    private val said = mutableSetOf<String>()

    override fun start() {
        if (state.value.phase != VoicePhase.Off) return
        state.value = VoiceChatState(VoicePhase.Starting, speaker = state.value.speaker)
        speech.authorize { error ->
            if (state.value.phase != VoicePhase.Starting) return@authorize
            if (error != null) state.value = VoiceChatState(error = error)
            else {
                state.update { it.copy(phase = VoicePhase.Listening) }
                hear()
            }
        }
    }

    override fun end() {
        reply?.cancel()
        speech.stop()
        state.value = VoiceChatState(speaker = state.value.speaker)
    }

    override fun pause() {
        if (state.value.phase == VoicePhase.Off) return
        cutIn()
        speech.stop()
        state.update { it.copy(phase = VoicePhase.Listening, paused = true, heard = "", spoken = emptyMap()) }
    }

    override fun resume() {
        if (!state.value.paused) return
        state.update { it.copy(paused = false) }
        hear()
    }

    override fun speaker(on: Boolean) {
        state.update { it.copy(speaker = on, spoken = if (on) it.spoken else emptyMap()) }
        if (!on) speech.stopSpeaking()
    }

    /**
     * Listens for the user's next turn. While the reply is spoken, words that aren't its echo cut it short; while it's
     * still thinking, only a whole sentence does, since silence and noise can be heard as a word or two.
     */
    private fun hear() {
        if (state.value.paused) return
        speech.listen(
            untilPause = true,
            onPartial = { text ->
                when (state.value.phase) {
                    VoicePhase.Listening -> state.update { it.copy(heard = text) }
                    VoicePhase.Speaking -> if (isUser(text)) {
                        cutIn()
                        state.update { it.copy(phase = VoicePhase.Listening, heard = text, spoken = emptyMap()) }
                    }
                    else -> {}
                }
            },
            onFinal = { text ->
                when (state.value.phase) {
                    VoicePhase.Listening -> if (text.isBlank()) hear() else send(text)
                    VoicePhase.Thinking -> if (isUser(text)) {
                        cutIn()
                        send(text)
                    } else hear()
                    VoicePhase.Speaking -> hear()
                    else -> {}
                }
            },
            onError = { error ->
                if (state.value.phase != VoicePhase.Off) {
                    end()
                    state.update { it.copy(error = error) }
                }
            },
        )
    }

    private fun isUser(text: String): Boolean {
        val words = words(text)
        return words.count { it !in said } >= 3
    }

    private fun cutIn() {
        reply?.cancel()
        speech.stopSpeaking()
        // A send still on its way starts its run once it lands, so stop after it.
        if (session.state.value.running) scope.launch {
            session.state.first { !it.sending }
            session.stop()
        }
    }

    private fun send(text: String) {
        state.update { it.copy(phase = VoicePhase.Thinking, heard = "") }
        said.clear()
        hear()
        reply = scope.launch {
            // A run that was just cut short may still be stopping.
            session.state.first { !it.running }
            val before = session.state.value.items.mapTo(mutableSetOf()) { it.id }
            session.send(text, voice = true)
            speakReply(before)
            state.update { if (it.phase == VoicePhase.Thinking || it.phase == VoicePhase.Speaking) it.copy(phase = VoicePhase.Listening, spoken = emptyMap()) else it }
        }
    }

    /** Speaks each finished sentence of the new replies while the run streams them, and returns once all is said. */
    private suspend fun speakReply(before: Set<String>) {
        val queued = mutableMapOf<String, Int>()
        val announced = mutableSetOf<String>()
        val pending = MutableStateFlow(0)
        // Spoken sentences in order, by message and where they end; the first is being read aloud.
        val playing = ArrayDeque<Pair<String?, Int>>()
        fun reach() {
            val (item, end) = playing.firstOrNull() ?: return
            if (item != null) state.update { s -> if (item in s.spoken) s.copy(spoken = s.spoken + (item to end)) else s }
        }
        fun say(text: String, item: String? = null, end: Int = 0) {
            if (!state.value.speaker) return
            val words = speakable(text).ifBlank { return }
            said += words(words)
            pending.update { it + 1 }
            state.update { s -> s.copy(phase = VoicePhase.Speaking, spoken = if (item != null && item !in s.spoken) s.spoken + (item to 0) else s.spoken) }
            playing.addLast(item to end)
            if (playing.size == 1) reach()
            speech.speak(words) {
                pending.update { it - 1 }
                playing.removeFirstOrNull()
                reach()
            }
        }
        // Sending finishes before the Server starts the run, so the reply is done only once the run has started and ended.
        var started = false
        session.state.first { s ->
            // The reply is what follows the message sent; older pages loading meanwhile come before it.
            val sent = s.items.indexOfLast { it is ChatItem.User && it.id !in before }
            val fresh = if (sent < 0) emptyList() else s.items.drop(sent + 1).filter { it.id !in before }
            val errors = s.items.filter { it is ChatItem.Error && it.id !in before }
            started = started || s.data?.live?.running == true || errors.isNotEmpty() || fresh.any { it is ChatItem.Assistant || it is ChatItem.ToolCall }
            val done = started && !s.running
            for (item in fresh + errors) when (item) {
                is ChatItem.Assistant -> {
                    val from = queued[item.id] ?: 0
                    val end = if (done) item.text.length else sentenceEnd(item.text, from)
                    if (end > from) {
                        queued[item.id] = end
                        say(item.text.substring(from, end), item.id, end)
                    }
                }
                is ChatItem.ToolCall -> if (item.waiting && announced.add(item.id)) say("I need your answer on screen.")
                is ChatItem.Error -> if (announced.add(item.id)) say("Something went wrong.")
                else -> {}
            }
            done
        }
        pending.first { it <= 0 }
    }
}

/** The end of the last complete sentence in [text] after [from], or [from] when there's none yet. */
internal fun sentenceEnd(text: String, from: Int): Int {
    var end = from
    for (i in from until text.length - 1) if (text[i] == '\n' || (text[i] in ".!?:;" && text[i + 1].isWhitespace())) end = i + 1
    return end
}

/** [markdown] as it should be read aloud. */
fun speakable(markdown: String) = markdown
    .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""^\s*(?:[-+]|\d+\.)\s+""", RegexOption.MULTILINE), "")
    .replace(Regex("""[*_`#>|~]"""), "")
    .trim()

internal fun words(text: String) = text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.split(Regex("""\s+""")).filter { it.isNotEmpty() }
