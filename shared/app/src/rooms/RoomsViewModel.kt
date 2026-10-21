package kinuvo.app.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class RoomsRepository(internal val connection: ConnectedServer) {
    suspend fun list() = connection.call { it.call(Api.Rooms, Unit) }
    suspend fun create(p: CreateRoomParams) = connection.call { it.call(Api.CreateRoom, p) }
    suspend fun sessions(id: String) = connection.call { it.call(Api.RoomSessions, RoomRef(id)) }
    suspend fun newSession(id: String) = connection.call { it.call(Api.CreateRoomSession, RoomRef(id)) }
    suspend fun view(room: String, session: String) = connection.call { it.call(Api.RoomSession, RoomSessionRef(room, session)) }
    suspend fun send(p: SendRoomParams) = connection.call { it.call(Api.SendRoom, p) }
    suspend fun stop(room: String, session: String) = connection.call { it.call(Api.StopRoom, RoomSessionRef(room, session)) }
    suspend fun approve(id: String, decision: ApprovalDecision) = connection.call { it.call(Api.Approve, ApprovalParams(id, decision)) }
    suspend fun answer(id: String, answers: Map<String, List<String>>?) = connection.call { it.call(Api.Answer, QuestionsParams(id, answers)) }
}

data class RoomsState(
    val rooms: List<Room> = emptyList(), val selected: Room? = null,
    val sessions: List<RoomSession> = emptyList(), val view: RoomView? = null,
    val text: String = "", val recipients: List<String> = emptyList(),
    val submitting: Boolean = false,
    val creating: Boolean = false, val name: String = "", val members: List<String> = emptyList(),
    val coordinator: String? = null, val error: String? = null,
)

class RoomsViewModel(private val repo: RoomsRepository) : ViewModel() {
    val state: StateFlow<RoomsState>
        field = MutableStateFlow(RoomsState())
    private var poll: Job? = null
    private var selection = 0L
    private var draft = 0L
    init { repo.connection.bind(viewModelScope); refresh() }
    fun refresh() = attempt { val rooms = repo.list(); state.update { it.copy(rooms = rooms) } }
    fun creating(value: Boolean) = state.update { it.copy(creating = value, error = null) }
    fun name(value: String) = state.update { it.copy(name = value) }
    fun member(id: String) = state.update {
        val members = if (id in it.members) it.members - id else it.members + id
        it.copy(members = members, coordinator = it.coordinator?.takeIf { c -> c in members } ?: members.firstOrNull())
    }
    fun coordinator(id: String) = state.update { if (id in it.members) it.copy(coordinator = id) else it }
    fun create() = attempt {
        val s = state.value
        require(s.name.isNotBlank() && s.members.size in 2..8 && s.coordinator in s.members) { "Name the Room, choose 2 to 8 members and a coordinator" }
        val room = repo.create(CreateRoomParams(s.name, s.members, requireNotNull(s.coordinator)))
        val rooms = repo.list()
        state.update { it.copy(creating = false, rooms = rooms, name = "", members = emptyList(), coordinator = null) }
        select(room)
    }
    fun select(room: Room) {
        val generation = ++selection
        poll?.cancel()
        state.update { it.copy(selected = room, sessions = emptyList(), view = null, error = null, submitting = false, recipients = emptyList(), text = "") }
        attempt {
            val sessions = repo.sessions(room.id)
            if (selection == generation) {
                state.update { it.copy(sessions = sessions) }
                sessions.firstOrNull()?.let { open(it) }
            }
        }
    }
    fun newSession(): Job {
        val room = state.value.selected ?: return viewModelScope.launch {}
        val generation = ++selection
        poll?.cancel()
        state.update { it.copy(submitting = false, error = null) }
        return attempt {
            val session = repo.newSession(room.id)
            if (selection == generation) open(session)
        }
    }
    fun open(session: RoomSession) {
        val generation = ++selection
        poll?.cancel()
        state.update { it.copy(view = RoomView(session), recipients = emptyList(), submitting = false, error = null) }
        poll = viewModelScope.launch {
            while (isActive) {
                try {
                    val view = repo.view(session.roomId, session.id)
                    if (selection != generation) return@launch
                    state.update { it.copy(view = view, sessions = listOf(view.session) + it.sessions.filter { old -> old.id != session.id }) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.update { it.copy(error = e.message) } }
                delay(700)
            }
        }
    }
    fun text(value: String) {
        if (value != state.value.text) draft++
        state.update { it.copy(text = value) }
    }
    fun recipient(id: String) {
        draft++
        state.update {
            if (id !in it.selected?.members.orEmpty()) it else it.copy(recipients = if (id in it.recipients) it.recipients - id else it.recipients + id)
        }
    }
    fun send(): Job {
        if (state.value.submitting) return viewModelScope.launch {}
        val s = state.value
        val generation = selection
        val revision = draft
        state.update { it.copy(submitting = true) }
        return attempt {
            try {
                val session = s.view?.session ?: error("Create a Session first")
                require(s.text.isNotBlank()) { "Write a message" }
                repo.send(SendRoomParams(session.roomId, session.id, s.text, s.recipients))
                if (selection == generation) state.update {
                    it.copy(text = if (draft == revision) "" else it.text,
                        recipients = if (draft == revision) emptyList() else it.recipients,
                        view = it.view?.copy(running = true))
                }
            } finally {
                if (selection == generation) state.update { it.copy(submitting = false) }
            }
        }
    }
    fun stop() = attempt { state.value.view?.session?.let { repo.stop(it.roomId, it.id) } }
    fun approve(id: String, decision: ApprovalDecision) = attempt { repo.approve(id, decision) }
    fun answer(id: String, answers: Map<String, List<String>>?) = attempt { repo.answer(id, answers) }
    private fun attempt(block: suspend () -> Unit): Job {
        val generation = selection
        return viewModelScope.launch {
            try {
                repo.connection.run { block() }
                if (selection == generation) state.update { it.copy(error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (selection == generation) state.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }
}
