package kinuvo.ui

import kinuvo.app.session.*

internal var nativeSpeech: SpeechEngine? = null
internal var nativeVoiceAudio: VoiceAudio? = null

fun installSpeechEngine(engine: SpeechEngine) { nativeSpeech = engine }
fun installVoiceAudio(audio: VoiceAudio) { nativeVoiceAudio = audio }
