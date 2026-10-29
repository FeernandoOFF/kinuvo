package kinuvo.voice

import kinuvo.protocol.*

val serverVoiceModels = listOf(
    whisper("tiny", "Tiny · smallest", "65176e2deb88badc814a94058666cadccc29b61c", 12937772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434", 89855401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
    whisper("base", "Base · larger", "bb53ee204431c90d314c1cc08d28d23e5b7927cc", 29120534, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11", 130672026, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
    VoiceModel("supertonic-3", "Supertonic 3 · 10 voices (voice 0)", VoiceModelKind.Synthesis, "OpenRAIL-M",
        "https://huggingface.co/Supertone/supertonic-3", "31 upstream languages; quality varies. Choose the spoken language below.",
        listOf(VoiceModelFile("model.tar.bz2", "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            128774318, "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427")),
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"),
)

private fun whisper(size: String, title: String, revision: String, encoderBytes: Long, encoderHash: String, decoderBytes: Long, decoderHash: String): VoiceModel {
    val source = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$size"
    val base = "$source/resolve/$revision"
    return VoiceModel("whisper-$size", "Whisper $title", VoiceModelKind.Transcription, "MIT", source,
        "Multilingual Whisper; accuracy varies by language, accent and microphone. Tiny favors size over accuracy.", listOf(
            VoiceModelFile("$size-encoder.int8.onnx", "$base/$size-encoder.int8.onnx", encoderBytes, encoderHash),
            VoiceModelFile("$size-decoder.int8.onnx", "$base/$size-decoder.int8.onnx", decoderBytes, decoderHash),
            VoiceModelFile("$size-tokens.txt", "$base/$size-tokens.txt", 816730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126")))
}
