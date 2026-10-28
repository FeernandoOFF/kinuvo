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

/** WAV clips are base64 encoded; audio stays out of Session history. */
@Serializable
data class TranscribeParams(val base64: String)

@Serializable
data class SynthesizeParams(val text: String)

@Serializable
data class SpeechAudio(val base64: String)

@Serializable
data class CancelVoiceParams(val requestId: String)
