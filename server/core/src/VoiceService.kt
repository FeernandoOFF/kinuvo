package kinuvo.server

import kinuvo.server.plugin.Audio
import kinuvo.server.plugin.AudioFormat
import kinuvo.server.voice.MAX_RECORDING_BYTES
import kinuvo.server.plugin.VoiceProviders
import kinuvo.protocol.voice.*
import kotlin.io.encoding.Base64

/** Speech through the models the Server downloaded and runs itself, else its configured OpenAI-compatible providers. */
class VoiceService internal constructor(private val harness: HarnessRuntime, private val changed: () -> Unit) {
    private val downloaded get() = harness.serverVoice

    /** A selected downloaded model takes precedence over configured OpenAI-compatible providers. */
    val providers get() = downloaded?.providers.let { local ->
        VoiceProviders(local?.transcriber ?: harness.voice.transcriber, harness.voice.processor, local?.synthesizer ?: harness.voice.synthesizer, harness.voice.speechToSpeech.takeIf { harness.liveVoiceEnabled })
    }

    fun liveSettings() = LiveVoiceSettings(harness.voice.speechToSpeech != null, harness.liveVoiceEnabled)

    fun saveLive(enabled: Boolean): LiveVoiceSettings {
        harness.liveVoiceEnabled = enabled
        changed()
        return liveSettings()
    }

    suspend fun models(): VoiceModelsState = downloaded?.state() ?: VoiceModelsState()

    fun setup() = providers.setup

    suspend fun download(id: String) = requireNotNull(downloaded).download(id)

    fun cancelDownload() = requireNotNull(downloaded).cancelDownload()

    suspend fun remove(id: String) { requireNotNull(downloaded).remove(id); changed() }

    suspend fun select(selection: VoiceSelection) { requireNotNull(downloaded).select(selection); changed() }

    fun language(language: String) = requireNotNull(downloaded).language(language)

    suspend fun transcribe(p: TranscribeParams): String {
        val provider = checkNotNull(providers.transcriber) { "Server transcription is not configured" }
        require(p.base64.length <= (MAX_RECORDING_BYTES + 2) / 3 * 4) { "Recording is too large" }
        return provider.transcribe(Audio(Base64.decode(p.base64), AudioFormat.Wav))
    }

    suspend fun synthesize(p: SynthesizeParams): SpeechAudio {
        val provider = checkNotNull(providers.synthesizer) { "Server synthesis is not configured" }
        return SpeechAudio(Base64.encode(provider.synthesize(p.text).bytes))
    }
}
