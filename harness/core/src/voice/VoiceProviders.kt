package kinuvo.harness.core.voice

import kinuvo.protocol.VoiceSetup
import kotlinx.coroutines.channels.ReceiveChannel

/** Audio the Server exchanges with providers and Devices; never written to disk. */
class Audio(val bytes: ByteArray, val format: AudioFormat)

sealed interface AudioFormat {
    data object Wav : AudioFormat
    /** Mono 16-bit little-endian PCM, the format realtime providers stream. */
    data class Pcm16(val sampleRate: Int) : AudioFormat
}

fun interface Transcriber {
    suspend fun transcribe(audio: Audio): String
}

/** Corrects a transcript before it becomes the user's message; it never answers or follows it. */
fun interface TranscriptProcessor {
    suspend fun process(transcript: String): String
}

/** Called per sentence chunk while the previous one plays, so short text must be fast. */
fun interface Synthesizer {
    suspend fun synthesize(text: String): Audio
}

/**
 * One live audio conversation with a speech-to-speech model (OpenAI Realtime, Gemini Live, a local model), one adapter
 * per vendor protocol. It returns when [input] closes or the provider ends. The model talks; Agent work goes through
 * [askAgent], which runs the Session's Agent with its tools and Approvals.
 */
interface SpeechToSpeech {
    /** What the model hears and speaks, e.g. 16 kHz in and 24 kHz out; Devices convert to it. */
    val input: AudioFormat.Pcm16
    val output: AudioFormat.Pcm16
    suspend fun converse(context: VoiceContext, input: ReceiveChannel<VoiceInput>, output: suspend (VoiceOutput) -> Unit, askAgent: suspend (String, suspend (AgentProgress) -> Unit) -> String)
}

/** What a conversation starts from: who the model speaks for, and the Session so far, oldest first. */
class VoiceContext(val instructions: String, val history: List<VoiceMessage> = emptyList())

data class VoiceMessage(val user: Boolean, val text: String)

sealed interface VoiceInput {
    class Audio(val pcm: ByteArray) : VoiceInput
    /** How much of reply [item] the user has actually heard. */
    data class Played(val item: String, val millis: Int) : VoiceInput
    /** The user cut reply [item] short after hearing [millis] of it; providers that detect interruptions themselves may ignore it. */
    data class Interrupt(val item: String, val millis: Int) : VoiceInput
    data object Pause : VoiceInput
    data object Resume : VoiceInput
}

sealed interface VoiceOutput {
    data object Ready : VoiceOutput
    data object Listening : VoiceOutput
    data object Thinking : VoiceOutput
    class Audio(val item: String, val pcm: ByteArray) : VoiceOutput
    /** What has been said so far in turn [item]; the [final] transcript replaces the partial ones. */
    data class Transcript(val item: String, val text: String, val user: Boolean, val final: Boolean = true) : VoiceOutput
    data class Done(val item: String) : VoiceOutput
    /** The user started talking over a reply. */
    data object Interrupted : VoiceOutput
}

/**
 * What a Server does for Voice chat. Missing parts stay on the Device, so the default leaves it all to the Device's
 * recognizer and voice; [speechToSpeech] replaces the whole pipeline. Devices learn only [setup], never endpoints or keys.
 */
class VoiceProviders(
    val transcriber: Transcriber? = null,
    val processor: TranscriptProcessor? = null,
    val synthesizer: Synthesizer? = null,
    val speechToSpeech: SpeechToSpeech? = null,
) {
    val setup get() = VoiceSetup(transcriber != null, processor != null, synthesizer != null, speechToSpeech != null)
}

sealed interface AgentProgress {
    data class ApprovalRequested(val tool: String, val summary: String) : AgentProgress
    data class ApprovalResolved(val allowed: Boolean?) : AgentProgress
    data class Question(val text: String) : AgentProgress
    data class Text(val text: String) : AgentProgress
}
