package kinuvo.harness.server

import kinuvo.protocol.*

interface ServerVoice {
    fun state(): VoiceModelsState
    fun setup(): VoiceSetup
    fun download(id: String)
    fun cancelDownload()
    suspend fun remove(id: String)
    suspend fun select(selection: VoiceSelection)
    fun language(language: String)
    suspend fun transcribe(recording: VoiceRecording): String
    suspend fun synthesize(text: VoiceText): VoiceClip
    suspend fun close()
}
