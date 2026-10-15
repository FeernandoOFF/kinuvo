package kinuvo.ui

import kinuvo.app.session.*

internal var nativeSpeech: SpeechEngine? = null

fun installSpeechEngine(engine: SpeechEngine) { nativeSpeech = engine }
