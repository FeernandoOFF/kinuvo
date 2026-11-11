package kinuvo.app.voice

typealias VoiceModelKind = kinuvo.protocol.VoiceModelKind
typealias ModelFile = kinuvo.protocol.ModelFile
typealias VoiceModel = kinuvo.protocol.VoiceModel
typealias LiveVoiceSettings = kinuvo.protocol.LiveVoiceSettings

data class LocalVoiceState(
    val models: List<VoiceModel> = emptyList(),
    val installed: Set<String> = emptySet(),
    val transcription: String? = null,
    val synthesis: String? = null,
    val language: String = "en",
    val downloading: String? = null,
    val downloaded: Long = 0,
    val removing: String? = null,
    val error: String? = null,
    val live: LiveVoiceSettings = LiveVoiceSettings(),
)


val voiceLanguages = linkedMapOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "ja" to "Japanese", "ko" to "Korean", "hi" to "Hindi",
    "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian", "uk" to "Ukrainian", "ar" to "Arabic",
    "tr" to "Turkish", "vi" to "Vietnamese", "id" to "Indonesian", "sv" to "Swedish",
    "bg" to "Bulgarian", "hr" to "Croatian", "cs" to "Czech", "da" to "Danish", "et" to "Estonian",
    "fi" to "Finnish", "el" to "Greek", "hu" to "Hungarian", "lv" to "Latvian", "lt" to "Lithuanian",
    "ro" to "Romanian", "sk" to "Slovak", "sl" to "Slovenian")
