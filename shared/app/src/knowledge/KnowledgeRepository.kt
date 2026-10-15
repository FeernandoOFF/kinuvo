package kinuvo.app.knowledge

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The latest changes Agents made to the connected Server's Knowledge, newest first; null until first loaded. */
class KnowledgeRepository(internal val connection: ConnectedServer) {
    val changes: StateFlow<List<KnowledgeChange>?>
        field = MutableStateFlow(null)

    private val scope get() = connection.scope
    private val client get() = connection.client
    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        clear()
        attached = scope.launch {
            launch { client.events(Api.KnowledgeChanged).collect(::put) }
            client.state.collect { if (it is ConnectionState.Connected && changes.value != null) load() }
        }
    }

    internal fun detach() { clear() }

    fun load() = scope.launch {
        val loaded = catching { client?.call(Api.KnowledgeChanges, KnowledgeChangesParams(LIMIT)) }.getOrNull() ?: return@launch
        changes.value = loaded
    }

    suspend fun search(query: String): List<NoteSummary> = call { it.call(Api.KnowledgeSearch, KnowledgeSearchParams(query)) }

    suspend fun note(ref: String): Note = call { it.call(Api.KnowledgeNote, NoteRef(ref)) }

    suspend fun undo(changeId: String): KnowledgeChange = connection.run { call { it.call(Api.UndoKnowledgeChange, KnowledgeChangeRef(changeId)) }.also { put(it) } }

    private fun put(change: KnowledgeChange) {
        changes.update { all -> all?.let { (listOf(change) + it.filterNot { c -> c.id == change.id }).sortedByDescending { c -> c.at }.take(LIMIT) } }
    }

    private suspend fun <T> call(block: suspend (KinuvoClient) -> T): T =
        connection.call(block)

    private fun clear() {
        attached?.cancel()
        changes.value = null
    }

    private companion object {
        const val LIMIT = 100
    }
}
