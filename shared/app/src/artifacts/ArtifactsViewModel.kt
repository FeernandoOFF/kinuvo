package kinuvo.app.artifacts

import kinuvo.app.connection.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** [all] is null until loaded; [open] is the [Route.Artifacts.artifact] being shown. */
data class ArtifactsState(
    val route: Route.Artifacts,
    val all: List<ArtifactInfo>? = null,
    val query: String = "",
    /** Only this Agent's Artifacts; null shows everyone's. */
    val agent: String? = null,
    val open: ArtifactData? = null,
    /** The open Artifact was deleted, or never existed. */
    val missing: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val shown get() = all.orEmpty().filter { (agent == null || it.agentId == agent) && (query.isBlank() || it.title.contains(query, ignoreCase = true)) }
}

/** Reloads on every change; a newer load cancels the one in flight, so a late reply can't undo a newer change. */
class ArtifactsViewModel(private val client: KinuvoClient, route: Route.Artifacts, private val owner: ConnectedServer) : ViewModel() {
    val state: StateFlow<ArtifactsState>
        field = MutableStateFlow(ArtifactsState(route))
    private var loading: Job? = null

    init {
        owner.bind(viewModelScope, client)
        viewModelScope.launch { client.state.collect { if (it is ConnectionState.Connected) load() } }
        viewModelScope.launch { client.events(Api.ArtifactChanged).collect { load() } }
    }

    fun search(query: String) = state.update { it.copy(query = query) }

    fun filter(agent: String?) = state.update { it.copy(agent = agent) }

    fun delete(id: String) {
        viewModelScope.launch { attempt { client.call(Api.DeleteArtifact, ArtifactRef(id)) } }
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            attempt {
                val all = client.call(Api.Artifacts, Unit)
                val id = state.value.route.artifact
                val open = id?.takeIf { all.any { a -> a.id == it } }?.let { client.call(Api.Artifact, ArtifactRef(it)) }
                state.update { it.copy(all = all, open = open, missing = id != null && open == null) }
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            owner.run { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(errors = it.errors + (e.message ?: e.toString())) }
        }
    }
}
