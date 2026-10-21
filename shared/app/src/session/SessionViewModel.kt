package kinuvo.app.session

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.client.ConnectionState
import kinuvo.client.DisconnectedException
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.app.session.CompletionSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ChatItem {
    val id: String

    /** [routine] names the Routine that sent [text] on its schedule. */
    data class User(override val id: String, val text: String, val routine: String? = null, val attachments: List<Attachment> = emptyList(), val task: String? = null) : ChatItem
    data class Assistant(override val id: String, val text: String) : ChatItem
    data class Reasoning(override val id: String, val text: String) : ChatItem
    /** [callId] is the model's tool-call ID; [approval] or [questions] is set while the call waits on the user. */
    data class ToolCall(
        override val id: String,
        val callId: String,
        val name: String,
        val args: String,
        val result: String? = null,
        val isError: Boolean = false,
        val approval: AgentEvent.ApprovalRequested? = null,
        val questions: AgentEvent.QuestionsAsked? = null,
        val identity: ToolIdentity? = null,
    ) : ChatItem {
        val awaitingApproval get() = approval != null
        val waiting get() = approval != null || questions != null
        val trustHint get() = approval?.trustHint
    }
    data class Measurement(override val id: String, val metrics: RequestMetrics) : ChatItem
    data class Error(override val id: String, val message: String) : ChatItem
    data class Reflection(override val id: String, val result: ReflectionResult? = null, val reviews: Map<String, Boolean> = emptyMap()) : ChatItem
    data class Compaction(override val id: String, val summary: String) : ChatItem
    data class Activation(override val id: String, val name: String, val content: String) : ChatItem
}

/** A transcript row: one item, or a run of reasoning and tool calls shown as one compact group. */
sealed interface ChatRow {
    val key: String

    data class Single(val item: ChatItem) : ChatRow {
        override val key get() = item.id
    }

    data class Steps(val steps: List<ChatItem>) : ChatRow {
        override val key get() = steps.first().id
        val tools get() = steps.filterIsInstance<ChatItem.ToolCall>()
        val failed get() = tools.count { it.isError }
    }
}

/** Groups consecutive steps; anything waiting on the user, and results the user should see, stay on their own row. */
fun List<ChatItem>.rows(): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var steps = mutableListOf<ChatItem>()
    for (item in this) {
        if (item is ChatItem.Measurement) continue
        if (item.isStep()) steps += item
        else {
            if (steps.isNotEmpty()) rows += ChatRow.Steps(steps).also { steps = mutableListOf() }
            rows += ChatRow.Single(item)
        }
    }
    if (steps.isNotEmpty()) rows += ChatRow.Steps(steps)
    return rows
}

fun List<ChatItem>.requestMetrics(): List<RequestMetrics> = flatMap {
    when (it) {
        is ChatItem.Measurement -> listOf(it.metrics)
        is ChatItem.Reflection -> it.result?.measurements.orEmpty()
        else -> emptyList()
    }
}.distinctBy { it.id }.sortedByDescending { it.at }

private fun ChatItem.isStep() = when (this) {
    is ChatItem.Reasoning -> true
    is ChatItem.ToolCall -> !waiting && !(name in setOf("ask_user", "artifact") && result != null && !isError)
    else -> false
}

/** [loaded] is false until the Session's first catch-up, so the empty state doesn't flash. */
data class ChatState(
    val agent: AgentInfo? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val selected: String? = null,
    /** The selected Session, for when it isn't in [sessions]. */
    val info: SessionInfo? = null,
    val items: List<ChatItem> = emptyList(),
    val loaded: Boolean = true,
    val hasOlder: Boolean = false,
    val running: Boolean = false,
    val completions: List<CompletionSource> = emptyList(),
    val settings: SessionSettings = SessionSettings(),
    /** The Agent's own settings, which unset [settings] follow. */
    val defaults: AgentSettings? = null,
    /** Discovered models from installed providers. */
    val models: ModelsCatalog = ModelsCatalog(),
    val attachments: List<PendingAttachment> = emptyList(),
    val unsent: String? = null,
    val sending: Boolean = false,
    /** What the Server and model can do in this Session; null until known, when nothing is disabled. */
    val capabilities: SessionCapabilities? = null,
    /** Voice chat and dictation; null when this Device can't do them. */
    val voice: VoiceChatState? = null,
    val dictation: DictationState? = null,
    /** The attachment open full screen. */
    val preview: AttachmentPreview? = null,
    /** Half-filled `ask_user` forms, as in [SessionState.answers]. */
    val answers: Map<String, Map<String, List<String>>> = emptyMap(),
) {
    val skills get() = capabilities?.server.supports(ServerFeature.Skills)
    /** Why nothing can be sent, or null when chat works. */
    val chatBlocked get() = capabilities?.chat?.takeUnless { it.actionable }?.let { it.reason ?: "Chat is unavailable" }
    val photos get() = capabilities?.attachable("image")
    val files get() = capabilities?.attachable("file")
}

private fun SessionCapabilities.attachable(kind: String) =
    if (ServerFeature.Attachments in server.features) input(kind) else CapabilityState.unsupported("Attachments are not available on this Server")

/**
 * [route] fills in as the Agent and Session resolve or a new Session is created. [data] is the Session as the
 * [SessionRepository] holds it; [errors] are this screen's failed requests.
 */
data class SessionState(
    val route: Route.Chat,
    val resolving: Boolean = true,
    /** The opened Session as it was resolved; its title stands in when the Session isn't in the sidebar, e.g. a Routine's. */
    val info: SessionInfo? = null,
    val data: SessionData? = null,
    val skills: List<SkillInfo> = emptyList(),
    val errors: List<String> = emptyList(),
    val sending: Boolean = false,
    /** The Session's settings, or those the next new Session starts with. */
    val settings: SessionSettings = SessionSettings(),
    val defaults: AgentSettings? = null,
    val models: ModelsCatalog = ModelsCatalog(),
    val attachments: List<PendingAttachment> = emptyList(),
    val downloads: Map<String, AttachmentData> = emptyMap(),
    /** The attachment open in the preview. */
    val preview: String? = null,
    /** The message being sent, shown until the Session's own copy of it arrives after [outgoingAfter]. */
    val outgoing: ChatItem.User? = null,
    val outgoingAfter: String? = null,
    /** Text of a message that failed to send, for the input to take back. */
    val unsent: String? = null,
    val capabilities: SessionCapabilities? = null,
    /** Half-filled `ask_user` forms by questions ID, then question ID, so they outlive the screen's layout. */
    val answers: Map<String, Map<String, List<String>>> = emptyMap(),
) {
    val loaded get() = !resolving && (route.sessionId == null || data?.loaded == true)
    val running get() = sending || data?.live?.running == true
    internal val echoed get() = outgoing == null || data?.items?.lastOrNull { it is ChatItem.User }?.id != outgoingAfter
    val items: List<ChatItem> get() = data?.items.orEmpty() + listOfNotNull(outgoing.takeUnless { echoed }) + errors.mapIndexed { i, e -> ChatItem.Error("error-$i", e) }
}

/** One Session screen: a view of the Session in the [SessionRepository], plus sending, stopping, Approvals and answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(private val client: KinuvoClient, private val repo: SessionRepository, route: Route.Chat) : ViewModel() {
    val state: StateFlow<SessionState>
        field = MutableStateFlow(SessionState(route))
    val connection: StateFlow<ConnectionState> = client.state
    private val route get() = state.value.route
    /** Settings changes and sends go one at a time, so a message runs with the settings picked before it. */
    private val ordered = Mutex()
    private val loadingAttachments = mutableSetOf<String>()

    init {
        repo.connection.bind(viewModelScope, client)
        viewModelScope.launch {
            state.map { it.route }.distinctUntilChanged()
                .flatMapLatest { r -> repo.state.map { it.sessions[r.sessionId] to it.skills[r.agentId].orEmpty() } }
                .distinctUntilChanged()
                .collect { (data, skills) -> state.update { it.copy(data = data, skills = skills).run { if (echoed) copy(outgoing = null) else this } } }
        }
        viewModelScope.launch {
            state.map { it.route.agentId }.distinctUntilChanged()
                .flatMapLatest { agent -> repo.connection.config.state.map { it[agent]?.details?.settings } }
                .distinctUntilChanged()
                .collect { defaults -> state.update { it.copy(defaults = defaults) } }
        }
        viewModelScope.launch {
            client.state.collect { if (it is ConnectionState.Connected) if (state.value.resolving) resolve() else refresh() }
        }
        viewModelScope.launch {
            state.map { it.route to (it.settings to it.defaults) }.distinctUntilChanged()
                .collect { (route, settings) -> route.agentId?.let { loadCapabilities(it, route.sessionId, settings.first) } }
        }
        viewModelScope.launch {
            client.events(Api.CapabilitiesChanged).collect { route.agentId?.let { loadCapabilities(it, route.sessionId, state.value.settings) } }
        }
        viewModelScope.launch {
            client.events(Api.SessionChanged).collect { info ->
                if (info.id == route.sessionId && !ordered.isLocked) state.update { it.copy(info = info, settings = info.settings) }
            }
        }
    }

    /** The screen is showing: keeps the Session cached and current. */
    fun shown() {
        if (repo.connection.client !== client) return
        if (!state.value.resolving) route.agentId?.let { repo.view(it, route.sessionId) }
    }

    /** Validates the saved route, falling back to the first Agent's latest Session. */
    private suspend fun resolve() = attempt {
        if (!state.value.resolving) return@attempt
        val requested = route
        val agents = client.call(Api.Agents, Unit)
        val requestedAgent = agents.firstOrNull { it.id == requested.agentId }
        val agent = requestedAgent ?: agents.firstOrNull()
        val session = when {
            agent == null -> null
            requestedAgent != null && requested.sessionId == null -> null
            requestedAgent != null && requested.sessionId != null && sessionExists(agent.id, requested.sessionId) -> requested.sessionId
            else -> client.call(Api.Sessions, SessionsParams(agent.id, limit = 1)).sessions.firstOrNull()
                ?.also { info -> state.update { it.copy(info = info, settings = info.settings) } }?.id
        }
        state.update { it.copy(route = it.route.copy(agentId = agent?.id, sessionId = session), resolving = false) }
        agent?.id?.let(repo::loadSkills)
        loadModels()
        agent?.id?.let { repo.connection.config.loadDetails(it) }
    }

    /** Catches up on the open Session's info, e.g. settings another Device changed while this one was offline. */
    private suspend fun refresh() {
        loadModels()
        val agent = route.agentId ?: return
        repo.connection.config.loadDetails(agent)
        loadCapabilities(agent, route.sessionId, state.value.settings)
        val sessionId = route.sessionId ?: return
        val info = catching { client.call(Api.Session, SessionParams(agent, sessionId)) }.getOrNull() ?: return
        if (info.id == route.sessionId && !ordered.isLocked) state.update { it.copy(info = info, settings = info.settings) }
    }

    private suspend fun loadCapabilities(agentId: String, sessionId: String?, settings: SessionSettings) {
        val defaults = state.value.defaults
        val capabilities = catching { client.call(Api.Capabilities, CapabilitiesParams(agentId, sessionId, settings)) }.getOrNull() ?: return
        state.update { if (it.route.agentId == agentId && it.route.sessionId == sessionId && it.settings == settings && it.defaults == defaults) it.copy(capabilities = capabilities) else it }
    }

    fun loadModels(refresh: Boolean = false) {
        viewModelScope.launch { attempt {
            val models = client.call(Api.Models, ModelsParams(refresh))
            state.update { it.copy(models = models) }
        } }
    }

    /** Applies to the open Session from its next run, or to the Session the next message starts. */
    fun configure(settings: SessionSettings) {
        state.update { it.copy(settings = settings) }
        val agent = route.agentId ?: return
        val sessionId = route.sessionId ?: return
        viewModelScope.launch { attempt { ordered.withLock { apply(agent, sessionId, settings) } } }
    }

    private suspend fun apply(agentId: String, sessionId: String, settings: SessionSettings) {
        val info = client.call(Api.ConfigureSession, ConfigureSessionParams(agentId, sessionId, settings))
        // A newer pick may be waiting; it wins.
        state.update { if (it.settings == settings) it.copy(settings = info.settings) else it }
    }

    /** Renames the open Session; blank goes back to its first message. */
    fun rename(title: String) = update(title = title)

    fun favorite(favorite: Boolean) = update(favorite = favorite)

    private fun update(title: String? = null, favorite: Boolean? = null) {
        val agent = route.agentId ?: return
        val sessionId = route.sessionId ?: return
        viewModelScope.launch { attempt {
            val info = repo.update(agent, sessionId, title, favorite)
            state.update { if (it.route.sessionId == info.id) it.copy(info = info) else it }
        } }
    }

    private suspend fun sessionExists(agentId: String, sessionId: String): Boolean =
        catching { client.call(Api.Session, SessionParams(agentId, sessionId)) }
            .onSuccess { info -> state.update { it.copy(info = info, settings = info.settings) } }.isSuccess

    /** [voice] marks [text] as transcribed from voice chat, so the reply suits being read aloud. */
    fun send(text: String, voice: Boolean = false) {
        val agent = route.agentId ?: return
        val pending = state.value.attachments
        if ((text.isBlank() && pending.isEmpty()) || state.value.running) return
        state.update { s ->
            s.copy(
                sending = true, attachments = emptyList(), unsent = null,
                outgoing = ChatItem.User("outgoing", text, attachments = pending.map { it.attachment.copy(id = it.id) }),
                outgoingAfter = s.data?.items?.lastOrNull { it is ChatItem.User }?.id,
                downloads = s.downloads + pending.associate { it.id to AttachmentData(it.attachment, it.upload.base64) },
            )
        }
        val uploads = mutableMapOf<String, Attachment>()
        viewModelScope.launch {
            try { repo.connection.run { ordered.withLock { sendNow(agent, text, pending, uploads, voice) } } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                state.update { it.copy(errors = it.errors + listOfNotNull(e.report()), outgoing = null, unsent = text, attachments = pending.map { p -> p.copy(uploaded = uploads[p.id] ?: p.uploaded) } + it.attachments) }
            }
            state.update { it.copy(sending = false) }
        }
    }

    private suspend fun sendNow(agent: String, text: String, pending: List<PendingAttachment>, uploads: MutableMap<String, Attachment>, voice: Boolean) {
        val sessionId = route.sessionId ?: repo.createSession(agent).let { info ->
            val id = info.id
            // Set first, so a pick made while these settings save queues up behind this send instead of being dropped.
            state.update { it.copy(route = it.route.copy(sessionId = id), info = it.info?.takeIf { i -> i.id == id } ?: info) }
            state.value.settings.takeIf { it != SessionSettings() }?.let { apply(agent, id, it) }
            repo.view(agent, id)
            id
        }
        val uploaded = pending.map { draft ->
            (draft.uploaded ?: client.call(Api.UploadAttachment, draft.upload).also { a ->
                uploads[draft.id] = a
                state.update { it.copy(downloads = it.downloads + (a.id to AttachmentData(a, draft.upload.base64))) }
            })
        }
        repo.send(agent, sessionId, text, uploaded.map { it.id }, voice)
    }

    fun addAttachments(files: List<UploadAttachmentParams>, error: String? = null) {
        if (error != null) { attachmentError(error); return }
        if (state.value.running || files.isEmpty()) return
        val pending = files.map { PendingAttachment(kotlin.uuid.Uuid.random().toString(), it) }
        val all = state.value.attachments + pending
        val issue = when {
            all.size > MAX_ATTACHMENTS -> "Attach up to 4 files"
            pending.any { attachmentMimeType(it.upload.name) == null } -> "This file type isn't supported"
            pending.any { it.attachment.size == 0 } -> "Empty files cannot be attached"
            pending.any { it.attachment.size > MAX_ATTACHMENT_BYTES } -> "Files must be 10 MB or smaller"
            all.sumOf { it.attachment.size } > MAX_ATTACHMENT_TOTAL_BYTES -> "Attachments must total 20 MB or less"
            else -> null
        }
        if (issue != null) attachmentError(issue) else state.update { it.copy(attachments = all) }
    }

    fun removeAttachment(id: String) {
        if (!state.value.running) state.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }

    fun attachmentError(message: String) = state.update { it.copy(errors = it.errors + message) }

    fun loadAttachment(id: String) {
        if (id in state.value.downloads || !loadingAttachments.add(id)) return
        viewModelScope.launch {
            try { attempt {
                val data = client.call(Api.Attachment, AttachmentRef(id))
                state.update { it.copy(downloads = (it.downloads + (id to data)).entries.toList().takeLast(8).associate { e -> e.toPair() }) }
            } } finally { loadingAttachments -= id }
        }
    }

    fun preview(id: String?) {
        state.update { it.copy(preview = id) }
        if (id != null && state.value.attachments.none { it.id == id }) loadAttachment(id)
    }

    fun saveAttachment(id: String, save: (AttachmentData) -> Unit) {
        viewModelScope.launch { attempt { save(state.value.downloads[id] ?: client.call(Api.Attachment, AttachmentRef(id))) } }
    }

    fun loadOlder() {
        if (repo.connection.client !== client) return
        route.sessionId?.let(repo::loadOlder)
    }

    fun respond(decision: ApprovalDecision) {
        val approval = state.value.data?.live?.approval ?: return
        viewModelScope.launch { attempt { repo.approve(approval.approvalId, decision) } }
    }

    fun reviewReflection(proposalId: String, approve: Boolean) {
        val sessionId = route.sessionId ?: return
        val agentId = route.agentId ?: return
        viewModelScope.launch { attempt {
            client.call(Api.ReviewReflection, kinuvo.protocol.ReviewReflectionParams(agentId, sessionId, proposalId, approve))
            if (approve) repo.loadSkills(agentId)
        } }
    }

    /** Keeps [value] as the answer to [questionId] in the `ask_user` form [questionsId] until it's sent. */
    fun editAnswer(questionsId: String, questionId: String, value: List<String>) =
        state.update { it.copy(answers = it.answers + (questionsId to it.answers[questionsId].orEmpty() + (questionId to value))) }

    /** Sends the open `ask_user` form's [answers]; null skips it. */
    fun answer(answers: Map<String, List<String>>?) {
        val questions = state.value.data?.live?.questions ?: return
        viewModelScope.launch { attempt {
            repo.answer(questions.questionsId, answers)
            state.update { it.copy(answers = it.answers - questions.questionsId) }
        } }
    }

    fun stop() {
        val sessionId = route.sessionId ?: return
        viewModelScope.launch { attempt { repo.stop(sessionId) } }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            repo.connection.run { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(errors = it.errors + listOfNotNull(e.report())) }
        }
    }

    /** What to show for a failed request; none when the connection dropped, which the connection banner shows. */
    private fun Exception.report() = takeUnless { it is DisconnectedException }?.let { message ?: toString() }
}
