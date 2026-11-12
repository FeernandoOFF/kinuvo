package kinuvo.protocol.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

/**
 * Which parts of Voice chat the Server provides; the Device does the rest with its own recognizer and voice. Endpoints
 * and keys stay on the Server. [speechToSpeech] replaces the whole pipeline.
 */
@Serializable
data class VoiceSetup(
    val transcriber: Boolean = false,
    val processor: Boolean = false,
    val synthesizer: Boolean = false,
    val speechToSpeech: Boolean = false,
)

@Serializable
enum class VoiceModelKind { Transcription, Synthesis }

@Serializable
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)
@Serializable
data class VoiceModel(
    val id: String,
    val title: String,
    val kind: VoiceModelKind,
    val license: String,
    val source: String,
    val languages: String,
    val files: List<ModelFile>,
    val archiveRoot: String? = null,
) {
    val bytes get() = files.sumOf { it.bytes }
}

@Serializable
data class VoiceModelsState(val models: List<VoiceModel> = emptyList(), val installed: Set<String> = emptySet(), val transcription: String? = null, val synthesis: String? = null, val language: String = "en", val downloading: String? = null, val downloaded: Long = 0, val error: String? = null, val supported: Boolean = false)
@Serializable
data class VoiceSelection(val kind: VoiceModelKind, val id: String? = null)

/** WAV clips are base64 encoded; audio stays out of Session history. */
@Serializable
data class TranscribeParams(val base64: String)

@Serializable
data class SynthesizeParams(val text: String)

@Serializable
data class SpeechAudio(val base64: String)

@Serializable
data class CancelVoiceParams(val requestId: String)

/** Starts a live speech-to-speech call in a Session; the Server holds the call to the model. */
@Serializable
data class LiveVoiceParams(val agentId: AgentId, val sessionId: SessionId)

/** Audio both ways is mono PCM16 at [sampleRate], base64 encoded. */
@Serializable
data class LiveVoiceCall(val callId: String, val sampleRate: Int)

@Serializable
data class LiveVoiceRef(val callId: String)

/** What the Device sends during a call, as events without replies: about 100 ms of microphone audio at a time. */
@Serializable
sealed interface LiveVoiceInput {
    val callId: String
    @Serializable @SerialName("audio") data class Audio(override val callId: String, val base64: String) : LiveVoiceInput
    /** How much of reply [item] the user has heard. */
    @Serializable @SerialName("played") data class Played(override val callId: String, val item: String, val millis: Int) : LiveVoiceInput
    @Serializable @SerialName("interrupt") data class Interrupt(override val callId: String, val item: String, val millis: Int) : LiveVoiceInput
    @Serializable @SerialName("pause") data class Pause(override val callId: String) : LiveVoiceInput
    @Serializable @SerialName("resume") data class Resume(override val callId: String) : LiveVoiceInput
}

@Serializable
sealed interface LiveVoiceOutput {
    val callId: String
    @Serializable @SerialName("ready") data class Ready(override val callId: String) : LiveVoiceOutput
    @Serializable @SerialName("listening") data class Listening(override val callId: String) : LiveVoiceOutput
    @Serializable @SerialName("thinking") data class Thinking(override val callId: String) : LiveVoiceOutput
    @Serializable @SerialName("audio") data class Audio(override val callId: String, val item: String, val base64: String) : LiveVoiceOutput
    /** What has been said so far in turn [item]; the [final] transcript replaces the partial ones. */
    @Serializable @SerialName("transcript") data class Transcript(override val callId: String, val item: String, val text: String, val user: Boolean, val final: Boolean = true) : LiveVoiceOutput
    @Serializable @SerialName("done") data class Done(override val callId: String, val item: String) : LiveVoiceOutput
    /** The user started talking over a reply; drop what is still queued to play. */
    @Serializable @SerialName("interrupted") data class Interrupted(override val callId: String) : LiveVoiceOutput
    /** The call is over, with [error] when it failed. */
    @Serializable @SerialName("ended") data class Ended(override val callId: String, val error: String? = null) : LiveVoiceOutput
}

/** Whether the Server can hold live speech-to-speech calls, and whether the owner turned them on. */
@Serializable
data class LiveVoiceSettings(val available: Boolean = false, val enabled: Boolean = true)
