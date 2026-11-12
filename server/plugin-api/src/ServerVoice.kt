package kinuvo.server.plugin

import kinuvo.protocol.*

/** Speech models the Server downloads and runs itself, managed from any client. */
interface ServerVoice {
    val providers: VoiceProviders
    fun state(): VoiceModelsState
    fun download(id: String)
    fun cancelDownload()
    suspend fun remove(id: String)
    suspend fun select(selection: VoiceSelection)
    fun language(language: String)
}
