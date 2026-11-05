package kinuvo.harness.server

import kinuvo.harness.core.voice.Audio
import kinuvo.harness.core.voice.AudioFormat
import kinuvo.harness.core.voice.MAX_RECORDING_BYTES
import kinuvo.harness.core.voice.VoiceProviders
import kinuvo.protocol.*
import kotlin.io.encoding.Base64

/** Speech through the models the Server downloaded and runs itself, else its configured OpenAI-compatible providers. */
internal class VoiceRequests(private val harness: HarnessRuntime, private val changed: () -> Unit) {
    private val downloaded get() = harness.serverVoice

    /** A selected downloaded model takes precedence over configured OpenAI-compatible providers. */
    val providers get() = downloaded?.providers.let { local ->
        VoiceProviders(local?.transcriber ?: harness.voice.transcriber, harness.voice.processor, local?.synthesizer ?: harness.voice.synthesizer, harness.voice.speechToSpeech)
    }

    fun <C> register(handlers: RequestHandlers<C>) = with(handlers) {
        on(Api.VoiceModels) { downloaded?.state() ?: VoiceModelsState() }
        on(Api.VoiceSetup) { providers.setup }
        on(Api.VoiceDownload) { requireNotNull(downloaded).download(it) }
        on(Api.VoiceCancelDownload) { requireNotNull(downloaded).cancelDownload() }
        on(Api.VoiceRemove) { requireNotNull(downloaded).remove(it); changed() }
        on(Api.VoiceSelect) { requireNotNull(downloaded).select(it); changed() }
        on(Api.VoiceLanguage) { requireNotNull(downloaded).language(it) }
        on(Api.Transcribe) { p ->
            val provider = checkNotNull(providers.transcriber) { "Server transcription is not configured" }
            require(p.base64.length <= (MAX_RECORDING_BYTES + 2) / 3 * 4) { "Recording is too large" }
            provider.transcribe(Audio(Base64.decode(p.base64), AudioFormat.Wav))
        }
        on(Api.Synthesize) { p ->
            val provider = checkNotNull(providers.synthesizer) { "Server synthesis is not configured" }
            SpeechAudio(Base64.encode(provider.synthesize(p.text).bytes))
        }
    }
}
