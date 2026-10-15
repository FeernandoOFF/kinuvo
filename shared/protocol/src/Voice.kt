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
