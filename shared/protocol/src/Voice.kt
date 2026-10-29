package kinuvo.protocol

import kotlinx.serialization.Serializable

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

@kotlinx.serialization.Serializable
enum class VoiceModelKind { Transcription, Synthesis }

@kotlinx.serialization.Serializable
data class VoiceModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)
@kotlinx.serialization.Serializable
data class VoiceModel(
    val id: String,
    val title: String,
    val kind: VoiceModelKind,
    val license: String,
    val source: String,
    val languages: String,
    val files: List<VoiceModelFile>,
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
