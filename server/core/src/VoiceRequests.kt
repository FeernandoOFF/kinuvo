package kinuvo.server

import kinuvo.server.plugin.Audio
import kinuvo.server.plugin.AudioFormat
import kinuvo.server.voice.MAX_RECORDING_BYTES
import kinuvo.server.plugin.VoiceProviders
import kinuvo.protocol.*
import kinuvo.protocol.voice.*
import kotlin.io.encoding.Base64

/** Speech through the models the Server downloaded and runs itself, else its configured OpenAI-compatible providers. */
internal class VoiceRequests(private val harness: HarnessRuntime, private val changed: () -> Unit) {
    private val downloaded get() = harness.serverVoice

    /** A selected downloaded model takes precedence over configured OpenAI-compatible providers. */
    val providers get() = downloaded?.providers.let { local ->
        VoiceProviders(local?.transcriber ?: harness.voice.transcriber, harness.voice.processor, local?.synthesizer ?: harness.voice.synthesizer, harness.voice.speechToSpeech.takeIf { harness.liveVoiceEnabled })
    }

    private fun liveSettings() = LiveVoiceSettings(harness.voice.speechToSpeech != null, harness.liveVoiceEnabled)

    suspend fun handle(r: Frame.Request): Frame.Response? = when (r.method) {
        Api.LiveVoiceSettings.name -> respond(r, Api.LiveVoiceSettings) { liveSettings() }
        Api.SaveLiveVoice.name -> respond(r, Api.SaveLiveVoice) { harness.liveVoiceEnabled = it; changed(); liveSettings() }
        Api.VoiceModels.name -> respond(r, Api.VoiceModels) { downloaded?.state() ?: VoiceModelsState() }
        Api.VoiceSetup.name -> respond(r, Api.VoiceSetup) { providers.setup }
        Api.VoiceDownload.name -> respond(r, Api.VoiceDownload) { requireNotNull(downloaded).download(it) }
        Api.VoiceCancelDownload.name -> respond(r, Api.VoiceCancelDownload) { requireNotNull(downloaded).cancelDownload() }
        Api.VoiceRemove.name -> respond(r, Api.VoiceRemove) { requireNotNull(downloaded).remove(it); changed() }
        Api.VoiceSelect.name -> respond(r, Api.VoiceSelect) { requireNotNull(downloaded).select(it); changed() }
        Api.VoiceLanguage.name -> respond(r, Api.VoiceLanguage) { requireNotNull(downloaded).language(it) }
        Api.Transcribe.name -> respond(r, Api.Transcribe) { p ->
            val provider = checkNotNull(providers.transcriber) { "Server transcription is not configured" }
            require(p.base64.length <= (MAX_RECORDING_BYTES + 2) / 3 * 4) { "Recording is too large" }
            provider.transcribe(Audio(Base64.decode(p.base64), AudioFormat.Wav))
        }
        Api.Synthesize.name -> respond(r, Api.Synthesize) { p ->
            val provider = checkNotNull(providers.synthesizer) { "Server synthesis is not configured" }
            SpeechAudio(Base64.encode(provider.synthesize(p.text).bytes))
        }
        else -> null
    }
}
