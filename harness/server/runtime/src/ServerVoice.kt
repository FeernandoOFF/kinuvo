package kinuvo.harness.server

import kinuvo.harness.core.voice.VoiceProviders
import kinuvo.protocol.*

interface ServerVoice {
    val providers: VoiceProviders
    fun state(): VoiceModelsState
    fun download(id: String)
    fun cancelDownload()
    suspend fun remove(id: String)
    suspend fun select(selection: VoiceSelection)
    fun language(language: String)
    suspend fun close()
}
