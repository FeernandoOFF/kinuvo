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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    /** The reveal.js deck in the open Artifact's page, once its adapter reported; null for any other page. */
    val deck: DeckStatus? = null,
) {
    /** The open Artifact changed since editing began. */
    val stale get() = draft != null && open != null && open.info.updated != draft.base
    val dirty get() = draft != null && draft.text != open?.content

    val shown get() = all.orEmpty().filter { (agent == null || it.agentId.value == agent.value) && (query.isBlank() || it.title.contains(query, ignoreCase = true)) }
}

/**
 * Reloads on every change; a newer load cancels the one in flight, so a late reply can't undo a newer change. A page with a
 * reveal.js deck also shares its position through the Server: the page's reports go out (at most one per [SEND_GAP]) and the
 * positions other Devices set come back through [pageCommands].
 */
class ArtifactsViewModel(route: Route.Artifacts, private val owner: ConnectedServer) : ViewModel() {
    private val client: KinuvoClient = requireNotNull(owner.client) { "Not connected" }
    private val cache = owner.artifacts
    val state: StateFlow<ArtifactsState>
        field = MutableStateFlow(ArtifactsState(route, all = cache.state.value.all, open = route.artifact?.let(cache::get)))
    private var loading: Job? = null
    private val designSaves = Mutex()
    private val outgoing = Channel<DeckPosition>(Channel.CONFLATED)
    private var watched: String? = null
    private var remote: DeckPosition? = null

    /** Messages for the open page: [deckApply] and [deckStep]. */
    val pageCommands: SharedFlow<String>
        field = MutableSharedFlow<String>(extraBufferCapacity = 16)

    init {
        owner.bind(viewModelScope, client)
        viewModelScope.launch { client.state.collect { if (it is ConnectionState.Connected) { load(); watched?.let(::watch) } } }
        viewModelScope.launch { client.events(Api.ArtifactChanged).collect { load() } }
        viewModelScope.launch {
            client.events(Api.ArtifactStateChanged).collect { change ->
                if (change.id != watched) return@collect
                deckPosition(change.state)?.let { remote = it; pageCommands.tryEmit(deckApply(it)) }
            }
        }
        viewModelScope.launch {
            for (position in outgoing) {
                val id = watched ?: continue
                try { client.call(Api.SetArtifactState, ArtifactStateSet(id, position.toState())) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                delay(SEND_GAP)
            }
        }
    }

    /** A message from the open page: the deck adapter's report, which is the only kind a page may send an HTML Artifact's host. */
    fun pageMessage(text: String) {
        val report = deckReport(text) ?: return
        val id = state.value.open?.info?.id ?: return
        state.update { it.copy(deck = report.status) }
        when {
            report.ready -> if (watched == id) remote?.let { pageCommands.tryEmit(deckApply(it)) } else { watched = id; remote = null; watch(id) }
            report.changed -> outgoing.trySend(report.status.position)
        }
    }

    /** Moves the open deck one step; the page reports the move back like any other, which shares it. */
    fun step(forward: Boolean) {
        pageCommands.tryEmit(deckStep(forward))
    }

    private fun watch(id: String) {
        viewModelScope.launch {
            try {
                val latest = deckPosition(client.call(Api.WatchArtifactState, ArtifactRef(id)).state)
                if (watched != id) return@launch
                remote = latest
                latest?.let { pageCommands.tryEmit(deckApply(it)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
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
                ArtifactData(info, draft.text).let { saved -> cache.put(saved); state.update { it.copy(open = saved, draft = null, errors = emptyList()) } }
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
                    // Finishes even if the screen closes, so the last edit is never dropped.
                    val info = withContext(NonCancellable) { client.call(Api.SaveArtifact, ArtifactSave(id, json)) }
                    cache.put(ArtifactData(info, json))
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
                cache.list(all)
                val id = state.value.route.artifact
                val listed = all.firstOrNull { it.id == id }
                // The copy this Device keeps stands while it's as new as the Server's; only a changed Artifact is fetched.
                val open = listed?.let { info -> cache.get(info.id)?.takeIf { it.info.updated == info.updated }?.copy(info = info) ?: client.call(Api.Artifact, ArtifactRef(info.id)) }
                open?.let(cache::put)
                state.update { it.copy(all = all, open = open, missing = id != null && open == null, draft = it.draft.takeIf { _ -> open?.info?.id == it.open?.info?.id }, deck = it.deck.takeIf { open != null }) }
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

private const val SEND_GAP = 250L
