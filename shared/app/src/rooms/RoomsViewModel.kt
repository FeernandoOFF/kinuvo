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
    suspend fun rename(p: UpdateRoomSessionParams) = connection.call { it.call(Api.UpdateRoomSession, p) }
    suspend fun view(room: String, session: String) = connection.call { it.call(Api.RoomSession, RoomSessionRef(room, session)) }
    suspend fun send(p: SendRoomParams) = connection.call { it.call(Api.SendRoom, p) }
    suspend fun stop(room: String, session: String) = connection.call { it.call(Api.StopRoom, RoomSessionRef(room, session)) }
    suspend fun approve(id: String, decision: ApprovalDecision) = connection.call { it.call(Api.Approve, ApprovalParams(id, decision)) }
    suspend fun answer(id: String, answers: Map<String, List<String>>?) = connection.call { it.call(Api.Answer, QuestionsParams(id, answers)) }
}

data class RoomsState(
    val rooms: List<Room> = emptyList(), val selected: Room? = null,
    val sessions: List<RoomSession> = emptyList(), val view: RoomView? = null,
    val roomSessions: Map<String, List<RoomSession>> = emptyMap(),
    val text: String = "", val recipients: List<String> = emptyList(),
    val submitting: Boolean = false,
    val creating: Boolean = false, val name: String = "", val members: List<String> = emptyList(),
    val error: String? = null,
)

class RoomsViewModel(private val repo: RoomsRepository) : ViewModel() {
    val state: StateFlow<RoomsState>
        field = MutableStateFlow(RoomsState())
    private var poll: Job? = null
    private var selection = 0L
    private var listing = 0L
    private var draft = 0L
    private val drafts = RoomDrafts()
    init { repo.connection.bind(viewModelScope); refresh() }
    fun refresh(): Job {
        val request = ++listing
        return attempt {
            val rooms = repo.list()
            if (request != listing) return@attempt
            if (state.value.selected?.let { selected -> rooms.none { it.id == selected.id } } == true) {
                saveDraft()
                poll?.cancel()
                selection++
            }
            state.update { current ->
                val selected = current.selected?.let { old -> rooms.firstOrNull { it.id == old.id } }
                current.copy(rooms = rooms, roomSessions = current.roomSessions.filterKeys { id -> rooms.any { it.id == id } }, selected = selected,
                    view = current.view.takeIf { selected != null }, sessions = current.sessions.takeIf { selected != null }.orEmpty(),
                    text = current.text.takeIf { selected != null }.orEmpty(), recipients = current.recipients.takeIf { selected != null }.orEmpty())
            }
            rooms.filterNot { it.direct }.forEach { room ->
                val sessions = repo.sessions(room.id)
                if (request != listing) return@attempt
                state.update { it.copy(roomSessions = it.roomSessions + (room.id to sessions)) }
            }
        }
    }
    fun creating(value: Boolean) = state.update { it.copy(creating = value, error = null) }
    fun name(value: String) = state.update { it.copy(name = value) }
    fun member(id: String) = state.update {
        val members = if (id in it.members) it.members - id else it.members + id
        it.copy(members = members)
    }
    fun create(): Job {
        val s = state.value
        val generation = selection
        return attempt {
            require(s.name.isNotBlank() && s.members.size in 2..8) { "Name the Room and choose 2 to 8 members" }
            val room = repo.create(CreateRoomParams(s.name.trim(), s.members))
            val rooms = repo.list()
            listing++
            state.update { it.copy(creating = false, rooms = rooms, name = "", members = emptyList()) }
            if (selection == generation) select(room)
        }
    }
    fun select(room: Room) {
        saveDraft()
        val generation = ++selection
        poll?.cancel()
        state.update { it.copy(selected = room, sessions = emptyList(), view = null, error = null, submitting = false, recipients = emptyList(), text = "") }
        attempt {
            val sessions = repo.sessions(room.id)
            if (selection == generation) {
                state.update { it.copy(sessions = sessions, roomSessions = it.roomSessions + (room.id to sessions)) }
                sessions.firstOrNull()?.let { open(it) }
            }
        }
    }
    fun newSession(room: Room? = state.value.selected): Job {
        room ?: return viewModelScope.launch {}
        saveDraft()
        val generation = ++selection
        poll?.cancel()
        state.update { s ->
            s.copy(selected = room, sessions = s.roomSessions[room.id] ?: if (s.selected?.id == room.id) s.sessions else emptyList(),
                view = null, text = "", recipients = emptyList(), submitting = false, error = null)
        }
        return attempt {
            val session = repo.newSession(room.id)
            if (selection == generation) open(session)
        }
    }
    fun open(session: RoomSession) {
        val room = state.value.rooms.firstOrNull { it.id == session.roomId } ?: state.value.selected?.takeIf { it.id == session.roomId } ?: return
        saveDraft()
        val saved = drafts.get(session.roomId, session.id)
        draft++
        val generation = ++selection
        poll?.cancel()
        state.update { it.copy(selected = room, sessions = listOf(session) + (it.roomSessions[room.id] ?: it.sessions).filter { old -> old.id != session.id }, view = RoomView(session), text = saved.text, recipients = saved.recipients, submitting = false, error = null) }
        poll = viewModelScope.launch {
            while (isActive) {
                try {
                    val view = repo.view(session.roomId, session.id)
                    if (selection != generation) return@launch
                    state.update {
                        val sessions = listOf(view.session) + it.sessions.filter { old -> old.id != session.id }
                        it.copy(view = view, sessions = sessions, roomSessions = it.roomSessions + (room.id to sessions))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (selection == generation) state.update { it.copy(error = e.message) } }
                delay(700)
            }
        }
    }
    fun text(value: String) {
        if (value != state.value.text) draft++
        state.update { it.copy(text = value) }
        saveDraft()
    }
    fun edit(value: RoomDraft, roomId: String?, sessionId: String?) {
        if (state.value.selected?.id == roomId && state.value.view?.session?.id == sessionId) edit(value)
    }
    fun edit(value: RoomDraft) {
        val recipients = value.recipients.filter { it in state.value.selected?.members.orEmpty() }.distinct()
        if (RoomDraft(state.value.text, state.value.recipients) != value.copy(recipients = recipients)) draft++
        state.update { it.copy(text = value.text, recipients = recipients) }
        saveDraft()
    }
    fun recipient(id: String) {
        draft++
        state.update {
            if (id !in it.selected?.members.orEmpty()) it else it.copy(recipients = if (id in it.recipients) it.recipients - id else it.recipients + id)
        }
        saveDraft()
    }
    fun rename(title: String): Job {
        val session = state.value.view?.session ?: return viewModelScope.launch {}
        val generation = selection
        return attempt {
            require(title.isNotBlank()) { "Name the Session" }
            repo.rename(UpdateRoomSessionParams(session.roomId, session.id, title.trim()))
            val sessions = repo.sessions(session.roomId)
            state.update { it.copy(roomSessions = it.roomSessions + (session.roomId to sessions)) }
            if (selection == generation) state.update { it.copy(sessions = sessions, view = it.view?.let { v -> v.copy(session = v.session.copy(title = title.trim())) }) }
        }
    }
    fun send(value: RoomDraft): Job {
        edit(value)
        return send()
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
                drafts.sent(session.roomId, session.id, RoomDraft(s.text, s.recipients), revision)
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
    private fun saveDraft() {
        state.value.let { s -> s.view?.session?.let { drafts.save(it.roomId, it.id, RoomDraft(s.text, s.recipients), draft) } }
    }
    fun stop(): Job {
        val session = state.value.view?.session
        return attempt { session?.let { repo.stop(it.roomId, it.id) } }
    }
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

data class RoomDraft(val text: String = "", val recipients: List<String> = emptyList())

internal class RoomDrafts {
    private data class Saved(val value: RoomDraft, val revision: Long)
    private val values = mutableMapOf<Pair<String, String>, Saved>()
    fun get(room: String, session: String) = values[room to session]?.value ?: RoomDraft()
    fun save(room: String, session: String, draft: RoomDraft, revision: Long) { values[room to session] = Saved(draft, revision) }
    fun sent(room: String, session: String, sent: RoomDraft, revision: Long) {
        if (values[room to session] == Saved(sent, revision)) values.remove(room to session)
    }
}

fun RoomSession.displayTitle() = title.ifBlank { messages.firstOrNull()?.text?.lineSequence()?.firstOrNull() ?: "New Session" }
    .replace(RoomMentionTitle) { it.groupValues[1] }

private val RoomMentionTitle = Regex("""\[(@[^]]+)]\(trigger:room-member:[^)]*\)?""")
