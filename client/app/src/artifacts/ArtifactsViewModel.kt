package kinuvo.app.artifacts

import kinuvo.client.userMessage
import kinuvo.app.connection.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.artifact.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** The open Artifact's source being edited; [base] is its [ArtifactInfo.updated] when editing began. */
data class ArtifactDraft(val text: String, val base: Long)

/** [all] is null until loaded; [open] is the [Route.Artifacts.artifact] being shown. */
data class ArtifactsState(
    val route: Route.Artifacts,
    val all: List<ArtifactInfo>? = null,
    val query: String = "",
    /** Only this Agent's Artifacts; null shows everyone's. */
    val agent: AgentId? = null,
    val open: ArtifactData? = null,
    /** The open Artifact was deleted, or never existed. */
    val missing: Boolean = false,
    val errors: List<String> = emptyList(),
    /** Null when not editing. */
    val draft: ArtifactDraft? = null,
    val saving: Boolean = false,
) {
    /** The open Artifact changed since editing began. */
    val stale get() = draft != null && open != null && open.info.updated != draft.base
    val dirty get() = draft != null && draft.text != open?.content

    val shown get() = all.orEmpty().filter { (agent == null || it.agentId.value == agent.value) && (query.isBlank() || it.title.contains(query, ignoreCase = true)) }
}

/** Reloads on every change; a newer load cancels the one in flight, so a late reply can't undo a newer change. */
class ArtifactsViewModel(private val client: KinuvoClient, route: Route.Artifacts, private val owner: ConnectedServer) : ViewModel() {
    val state: StateFlow<ArtifactsState>
        field = MutableStateFlow(ArtifactsState(route))
    private var loading: Job? = null
    private val designSaves = Mutex()

    init {
        owner.bind(viewModelScope, client)
        viewModelScope.launch { client.state.collect { if (it is ConnectionState.Connected) load() } }
        viewModelScope.launch { client.events(Api.ArtifactChanged).collect { load() } }
    }

    fun search(query: String) = state.update { it.copy(query = query) }

    fun filter(agent: AgentId?) = state.update { it.copy(agent = agent) }

    fun delete(id: String) {
        viewModelScope.launch { attempt { client.call(Api.DeleteArtifact, ArtifactRef(id)) } }
    }

    fun edit() = state.update { s -> s.open?.let { s.copy(draft = ArtifactDraft(it.content, it.info.updated), errors = emptyList()) } ?: s }

    fun change(text: String) = state.update { if (it.draft != null) it.copy(draft = it.draft.copy(text = text)) else it }

    /** Keeps the draft over the newer version. */
    fun keepMine() = state.update { s -> s.copy(draft = s.draft?.copy(base = s.open?.info?.updated ?: s.draft.base)) }

    fun cancel() {
        state.update { it.copy(draft = null) }
    }

    fun save() {
        val s = state.value
        val draft = s.draft ?: return
        val id = s.open?.info?.id ?: return
        if (s.saving) return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            attempt {
                val info = client.call(Api.SaveArtifact, ArtifactSave(id, draft.text))
                state.update { it.copy(open = ArtifactData(info, draft.text), draft = null, errors = emptyList()) }
            }
            state.update { it.copy(saving = false) }
        }
    }

    /** A canvas edit: shown in [ArtifactsState.open] at once, saved in order without a draft; a failed save reloads the Server's version. */
    fun saveDesign(json: String) {
        val id = state.value.open?.info?.id ?: return
        state.update { s -> s.open?.let { s.copy(open = it.copy(content = json)) } ?: s }
        viewModelScope.launch {
            designSaves.withLock {
                val failed = state.value.errors.size
                attempt {
                    val info = client.call(Api.SaveArtifact, ArtifactSave(id, json))
                    state.update { if (it.open?.info?.id == id) it.copy(open = ArtifactData(info, json), errors = emptyList()) else it }
                }
                if (state.value.errors.size > failed) load()
            }
        }
    }

    private fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            attempt {
                val all = client.call(Api.Artifacts, Unit)
                val id = state.value.route.artifact
                val open = id?.takeIf { all.any { a -> a.id == it } }?.let { client.call(Api.Artifact, ArtifactRef(it)) }
                state.update { it.copy(all = all, open = open, missing = id != null && open == null, draft = it.draft.takeIf { _ -> open?.info?.id == it.open?.info?.id }) }
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            owner.run { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(errors = it.errors + (e.userMessage)) }
        }
    }
}
