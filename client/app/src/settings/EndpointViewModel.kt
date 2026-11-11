package kinuvo.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.agent.ConfigRepository
import kinuvo.protocol.CustomEndpointParams
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ProviderRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EndpointState(
    val params: CustomEndpointParams = CustomEndpointParams(baseUrl = ""),
    val models: List<ModelInfo> = emptyList(),
    val discovered: Boolean = false,
    val busy: Boolean = false,
    val reason: String? = null,
    val error: String? = null,
) {
    val manual get() = discovered && models.isEmpty()
    val saveable get() = discovered && !busy && params.model.isNotBlank() && params.id.isNotBlank()
}

class EndpointViewModel(private val discover: suspend (CustomEndpointParams) -> List<ModelInfo>,
    private val save: suspend (CustomEndpointParams) -> ProviderRef) : ViewModel() {
    constructor(config: ConfigRepository) : this(config::discoverEndpoint, config::saveEndpoint)
    val state: StateFlow<EndpointState>
        field = MutableStateFlow(EndpointState())
    private var work: Job? = null

    fun edit(params: CustomEndpointParams) {
        if (params == state.value.params) return
        work?.cancel()
        state.update { if (params.baseUrl != it.params.baseUrl || params.apiKey != it.params.apiKey)
            EndpointState(params.copy(model = "")) else it.copy(params = params, busy = false, error = null) }
    }

    fun discover() {
        work?.cancel()
        val params = state.value.params
        work = viewModelScope.launch {
            state.update { it.copy(busy = true, error = null, reason = null, discovered = false, models = emptyList()) }
            try {
                val models = discover(params).filter { it.output == null || "text" in it.output.orEmpty() }.distinctBy { it.id }
                state.update { it.copy(models = models, discovered = true, busy = false,
                    params = params.copy(model = models.firstOrNull()?.id.orEmpty()),
                    reason = if (models.isEmpty()) "No chat models were returned. Enter a Model ID to continue." else null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(discovered = true, busy = false,
                reason = "Model discovery failed. Check the URL and key, or enter a Model ID.") } }
        }
    }

    fun save(onSaved: (String, String) -> Unit) {
        if (!state.value.saveable) return
        val params = state.value.params
        work = viewModelScope.launch {
            state.update { it.copy(busy = true, error = null) }
            try {
                val provider = save(if (state.value.manual) params else params.copy(model = ""))
                state.value = EndpointState()
                onSaved(provider.provider, params.model)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(busy = false, error = e.message ?: "Couldn't save provider.") } }
        }
    }
}
