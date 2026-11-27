package kinuvo.server.plugin

import kinuvo.protocol.voice.VoiceSetup

/** Audio the Server exchanges with providers and Devices; never written to disk. */
class Audio(val bytes: ByteArray, val format: AudioFormat)

sealed interface AudioFormat {
    data object Wav : AudioFormat
    /** Mono 16-bit little-endian PCM. */
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
 * What a Server does for Voice chat. Missing parts stay on the Device, so the default leaves it all to the Device's
 * recognizer and voice; [liveCalls] replaces the whole pipeline with a call the Device holds.
 * Devices learn only [setup], never endpoints or keys.
 */
class VoiceProviders(
    val transcriber: Transcriber? = null,
    val processor: TranscriptProcessor? = null,
    val synthesizer: Synthesizer? = null,
    val liveCalls: LiveCallProvider? = null,
) {
    val setup get() = VoiceSetup(transcriber != null, processor != null, synthesizer != null, liveCalls != null)
}
