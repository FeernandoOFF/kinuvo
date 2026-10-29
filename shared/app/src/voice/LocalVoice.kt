package kinuvo.app.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

typealias VoiceModelKind = kinuvo.protocol.VoiceModelKind
typealias VoiceModelFile = kinuvo.protocol.VoiceModelFile
typealias VoiceModel = kinuvo.protocol.VoiceModel

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
)

interface LocalVoiceRepository {
    val models: List<VoiceModel>
    fun installed(): Set<String>
    fun selected(kind: VoiceModelKind): String?
    fun select(kind: VoiceModelKind, id: String?)
    var language: String
    suspend fun download(model: VoiceModel, progress: (Long) -> Unit)
    suspend fun remove(model: VoiceModel)
}

/** Device preferences and downloads never pass through the connected Server. */
class LocalVoiceViewModel(private val repository: LocalVoiceRepository) : ViewModel() {
    val state: StateFlow<LocalVoiceState>
        field = MutableStateFlow(LocalVoiceState(repository.models, repository.installed(),
            repository.selected(VoiceModelKind.Transcription), repository.selected(VoiceModelKind.Synthesis), repository.language))
    private var download: Job? = null

    fun download(id: String) {
        if (download?.isCompleted == false || state.value.removing != null) return
        val model = repository.models.firstOrNull { it.id == id } ?: return
        state.update { it.copy(downloading = id, downloaded = 0, error = null) }
        download = viewModelScope.launch {
            try {
                repository.download(model) { bytes -> state.update { it.copy(downloaded = bytes) } }
                state.update { it.copy(installed = repository.installed()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "Download failed. Retry.") } }
            finally { state.update { it.copy(downloading = null, downloaded = 0) } }
        }
    }

    fun cancel() { download?.cancel() }
    fun select(kind: VoiceModelKind, id: String?) {
        if (id != null && (id !in state.value.installed || repository.models.none { it.id == id && it.kind == kind })) return
        repository.select(kind, id)
        state.update { if (kind == VoiceModelKind.Transcription) it.copy(transcription = id) else it.copy(synthesis = id) }
    }
    fun language(value: String) {
        if (value !in voiceLanguages.keys) return
        repository.language = value
        state.update { it.copy(language = value) }
    }
    fun remove(id: String) {
        if (state.value.downloading != null || state.value.removing != null) return
        val model = repository.models.firstOrNull { it.id == id } ?: return
        state.update { it.copy(removing = id) }
        viewModelScope.launch {
            try {
                repository.remove(model)
                if (repository.selected(model.kind) == id) select(model.kind, null)
                state.update { it.copy(installed = repository.installed(), error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "Cannot remove model") } }
            finally { state.update { it.copy(removing = null) } }
        }
    }
    override fun onCleared() { cancel() }
}

val voiceLanguages = linkedMapOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "ja" to "Japanese", "ko" to "Korean", "hi" to "Hindi",
    "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian", "uk" to "Ukrainian", "ar" to "Arabic",
    "tr" to "Turkish", "vi" to "Vietnamese", "id" to "Indonesian", "sv" to "Swedish",
    "bg" to "Bulgarian", "hr" to "Croatian", "cs" to "Czech", "da" to "Danish", "et" to "Estonian",
    "fi" to "Finnish", "el" to "Greek", "hu" to "Hungarian", "lv" to "Latvian", "lt" to "Lithuanian",
    "ro" to "Romanian", "sk" to "Slovak", "sl" to "Slovenian")
