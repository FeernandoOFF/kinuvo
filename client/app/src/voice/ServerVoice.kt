package kinuvo.app.voice

import kinuvo.client.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.catching
import kinuvo.protocol.*
import kinuvo.protocol.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ServerVoiceViewModel(private val server: ConnectedServer) : ViewModel() {
    val state: StateFlow<LocalVoiceState>
        field = MutableStateFlow(LocalVoiceState())
    private var actionError: String? = null
    init { viewModelScope.launch { while (isActive) { refresh(); delay(500) } } }
    private suspend fun refresh() {
        try {
            val s = server.call { it.call(Api.VoiceModels, Unit) }
            val live = catching { server.call { it.call(Api.LiveVoiceSettings, Unit) } }.getOrDefault(LiveVoiceSettings())
            state.value = LocalVoiceState(s.models, s.installed, s.transcription, s.synthesis, s.language, s.downloading, s.downloaded, error = s.error ?: actionError ?: if (!s.supported) "Downloaded voice inference requires a packaged macOS Server." else null, live = live)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.update { it.copy(error = userMessage(e.message) ?: "Server voice unavailable") } }
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block(); actionError = null; refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { actionError = userMessage(e.message) ?: "Voice action failed"; state.update { it.copy(error = actionError) } }
    } }
    fun retry() = action {}
    fun download(id: String) = action { server.call { it.call(Api.VoiceDownload, id) } }
    fun cancel() = action { server.call { it.call(Api.VoiceCancelDownload, Unit) } }
    fun remove(id: String) = action { server.call { it.call(Api.VoiceRemove, id) } }
    fun select(kind: VoiceModelKind, id: String?) = action { server.call { it.call(Api.VoiceSelect, VoiceSelection(kind, id)) } }
    fun language(value: String) = action { server.call { it.call(Api.VoiceLanguage, value) } }
    fun live(enabled: Boolean) = action { server.call { it.call(Api.SaveLiveVoice, enabled) } }
}
