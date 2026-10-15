package kinuvo.app.session

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** [text] is what's been dictated, kept after it stops until the next dictation, so the composer gets the final words. */
data class DictationState(val dictating: Boolean = false, val text: String = "", val error: String? = null)

/** Dictation into the composer: transcribes until stopped, never sends. */
class DictationViewModel(private val transcriber: Transcriber) : ViewModel() {
    val state: StateFlow<DictationState>
        field = MutableStateFlow(DictationState())

    /** Starts dictating, or stops. */
    fun toggle() {
        if (state.value.dictating) return stop()
        state.value = DictationState(dictating = true)
        transcriber.authorize { error ->
            if (!state.value.dictating) return@authorize
            if (error != null) fail(error)
            else transcriber.listen(
                untilPause = false,
                onPartial = { text -> state.update { if (it.dictating) it.copy(text = text) else it } },
                onFinal = { text -> state.update { if (it.dictating) it.copy(text = text) else it }; stop() },
                onError = ::fail,
            )
        }
    }

    fun stop() {
        if (!state.value.dictating) return
        transcriber.stop()
        state.update { it.copy(dictating = false) }
    }

    /** Stops and drops what was dictated, e.g. once it's been sent. */
    fun discard() {
        stop()
        state.value = DictationState()
    }

    private fun fail(error: String) {
        transcriber.stop()
        state.value = DictationState(error = error)
    }

    override fun onCleared() = stop()
}
