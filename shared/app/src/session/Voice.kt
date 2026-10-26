package kinuvo.app.session

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kinuvo.protocol.VoiceSetup

/** Turns what the user says into text as they talk. Callbacks arrive on the main thread. */
interface Transcriber {
    /** Asks for microphone and speech recognition access; [completion] gets why it's denied, or null. */
    fun authorize(completion: (String?) -> Unit)
    /** Transcribes the microphone, replacing any earlier listen; with [untilPause], [onFinal] fires once the user pauses. */
    fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    /** Stops listening and releases the microphone. */
    fun stop()
}

/** Reads text aloud. Callbacks arrive on the main thread. */
interface Synthesizer {
    /** Queues [text] after anything still being spoken; [onDone] fires once it's spoken or stopped. */
    fun speak(text: String, onDone: () -> Unit)
    fun stopSpeaking()
}

/**
 * The Device's own recognizer and voice (Apple's on iOS): the default for every part of Voice chat the Server's
 * [VoiceSetup] doesn't provide. Both share one audio session, so the microphone can stay open while speaking.
 */
interface SpeechEngine : Transcriber, Synthesizer {
    /** Stops listening and speaking and releases the audio. */
    override fun stop()
}

enum class VoicePhase { Off, Starting, Listening, Thinking, Speaking }

/**
 * [heard] is what the user is saying. [spoken] holds the reply's messages being read aloud, by item ID, with how far
 * into their text speech has reached. [paused] stops listening until resumed; [holding] listens through pauses while
 * the user holds to talk. [speaker] reads replies aloud.
 */
data class VoiceChatState(
    val phase: VoicePhase = VoicePhase.Off,
    val heard: String = "",
    val spoken: Map<String, Int> = emptyMap(),
    val paused: Boolean = false,
    val holding: Boolean = false,
    val speaker: Boolean = true,
    val error: String? = null,
) {
    val active get() = phase != VoicePhase.Off || error != null
}

/**
 * A voice conversation in the open Session. [SpeechVoiceChat] turns speech into messages and replies into speech; a
 * Server's speech-to-speech model would be another implementation behind the same controls.
 */
interface VoiceChat {
    val state: StateFlow<VoiceChatState>
    fun start()
    fun end()
    /** Cuts any reply short and stops listening until [resume]. */
    fun pause()
    fun resume()
    /** Starts the user's turn by hand, cutting any reply short; it lasts through pauses until [letGo]. */
    fun hold()
    /** Ends a [hold] and sends what was heard; hands-free listening carries on after the reply unless paused. */
    fun letGo()
    fun speaker(on: Boolean)
    /** The app went out of view, e.g. for the photo picker: frees the microphone but stays in voice chat for [foreground]. */
    fun background()
    /** Back in view: resumes what [background] paused. */
    fun foreground()
}

/** The Device does every part for now; Server-provided ones ([VoiceSetup]) get their Device client here. */
class VoiceViewModel(device: SpeechEngine, session: SessionViewModel) : ViewModel() {
    val chat: VoiceChat = SpeechVoiceChat(device, session, viewModelScope)

    init {
        viewModelScope.launch {
            session.state.map { it.voiceRequested }.distinctUntilChanged().filter { it }.collect { session.voiceStarted(); chat.start() }
        }
    }

    override fun onCleared() = chat.end()
}

/**
 * Full duplex over a [Transcriber] and a [Synthesizer], the [device]'s unless given others: it keeps listening while
 * the reply streams and is spoken, and the user talking over it cuts it short and starts their next turn.
 */
class SpeechVoiceChat(
    private val device: SpeechEngine,
    private val session: SessionViewModel,
    private val scope: CoroutineScope,
    private val transcriber: Transcriber = device,
    private val synthesizer: Synthesizer = device,
) : VoiceChat {
    override val state: StateFlow<VoiceChatState>
        field = MutableStateFlow(VoiceChatState())
    private var reply: Job? = null
    /** Words of the reply spoken so far, to tell the user talking from the reply's echo. */
    private val said = mutableSetOf<String>()
    private var resumeInView = false

    override fun start() {
        if (state.value.phase != VoicePhase.Off) return
        state.value = VoiceChatState(VoicePhase.Starting, speaker = state.value.speaker)
        transcriber.authorize { error ->
            if (state.value.phase != VoicePhase.Starting) return@authorize
            if (error != null) state.value = VoiceChatState(error = error)
            else {
                state.update { it.copy(phase = VoicePhase.Listening) }
                hear()
            }
        }
    }

    override fun end() {
        resumeInView = false
        reply?.cancel()
        release()
        state.value = VoiceChatState(speaker = state.value.speaker)
    }

    override fun pause() {
        if (state.value.phase == VoicePhase.Off) return
        cutIn()
        release()
        state.update { it.copy(phase = VoicePhase.Listening, paused = true, heard = "", spoken = emptyMap()) }
    }

    override fun resume() {
        if (!state.value.paused) return
        state.update { it.copy(paused = false) }
        hear()
    }

    override fun hold() {
        if (state.value.phase in setOf(VoicePhase.Off, VoicePhase.Starting) || state.value.holding) return
        cutIn()
        state.update { it.copy(phase = VoicePhase.Listening, holding = true, heard = "", spoken = emptyMap()) }
        transcriber.listen(
            untilPause = false,
            onPartial = { text -> if (state.value.holding) state.update { it.copy(heard = text) } },
            onFinal = { text -> if (state.value.holding) state.update { it.copy(heard = text) } },
            onError = ::fail,
        )
    }

    override fun letGo() {
        if (!state.value.holding) return
        val text = state.value.heard
        transcriber.stop()
        state.update { it.copy(holding = false) }
        if (text.isBlank()) hear() else send(text)
    }

    override fun background() {
        if (state.value.phase == VoicePhase.Off || state.value.paused) return
        resumeInView = true
        // Unlike pause, the run carries on: only the microphone and speech stop.
        reply?.cancel()
        release()
        state.update { it.copy(phase = VoicePhase.Listening, paused = true, holding = false, heard = "", spoken = emptyMap()) }
    }

    override fun foreground() {
        if (!resumeInView) return
        resumeInView = false
        resume()
    }

    override fun speaker(on: Boolean) {
        state.update { it.copy(speaker = on, spoken = if (on) it.spoken else emptyMap()) }
        if (!on) synthesizer.stopSpeaking()
    }

    /**
     * Listens for the user's next turn. While the reply is spoken, words that aren't its echo cut it short; while it's
     * still thinking, only a whole sentence does, since silence and noise can be heard as a word or two.
     */
    private fun hear() {
        if (state.value.paused) return
        transcriber.listen(
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
            onError = ::fail,
        )
    }

    private fun fail(error: String) {
        if (state.value.phase != VoicePhase.Off) {
            end()
            state.update { it.copy(error = error) }
        }
    }

    private fun release() {
        synthesizer.stopSpeaking()
        if (transcriber !== device) transcriber.stop()
        device.stop()
    }

    private fun isUser(text: String) = interrupts(text, said)

    private fun cutIn() {
        reply?.cancel()
        synthesizer.stopSpeaking()
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
            synthesizer.speak(words) {
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
                // A sentence at a time, so the next is ready to play before the one before it ends, however much arrived at once.
                is ChatItem.Assistant -> for (end in sentenceEnds(item.text, queued[item.id] ?: 0) + if (done) listOf(item.text.length) else emptyList()) {
                    val from = queued[item.id] ?: 0
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

/**
 * Whether [heard] is the user talking rather than the echo of the reply's [said] words: most of what was heard last isn't
 * the reply. Only the latest words count, so echo misheard over a long reply doesn't add up to an interruption.
 */
internal fun interrupts(heard: String, said: Set<String>): Boolean {
    val recent = words(heard).takeLast(8)
    val new = recent.count { it !in said }
    return new >= 3 && new * 2 > recent.size
}

/** The ends of the complete sentences in [text] after [from]. */
internal fun sentenceEnds(text: String, from: Int) = (from until text.length - 1)
    .filter { text[it] == '\n' || (text[it] in ".!?:;" && text[it + 1].isWhitespace()) }.map { it + 1 }

/** [markdown] as it should be read aloud. */
fun speakable(markdown: String) = markdown
    .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""^\s*(?:[-+]|\d+\.)\s+""", RegexOption.MULTILINE), "")
    .replace(Regex("""[*_`#>|~]"""), "")
    .trim()

internal fun words(text: String) = text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.split(Regex("""\s+""")).filter { it.isNotEmpty() }
