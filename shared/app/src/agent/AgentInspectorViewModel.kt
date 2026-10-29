package kinuvo.app.agent

import kinuvo.app.connection.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The inspector's levels: the Agent's settings, and the pickers and pages opened from them. */
enum class InspectorPage { Agent, Model, Approvals, UserNotes }

/** The Agent defaults the inspector edits, every Session's unless a Session sets its own. */
data class AgentDraft(
    val name: String,
    val identity: String,
    val soul: String,
    val provider: String,
    val model: String,
    val reasoning: String,
    val approvals: String,
    /** An ACP Agent's working folder; [kind] decides what the draft can change. */
    val cwd: String = "",
    val kind: ExecutionKind = ExecutionKind.Native,
) {
    /** Only what differs from [details]; an external Agent's edits are in [registration] instead. */
    fun changes(details: AgentDetails): UpdateAgentParams {
        val s = details.settings
        if (kind != ExecutionKind.Native) return UpdateAgentParams(details.info.id)
        return UpdateAgentParams(
            details.info.id,
            name = name.trim().takeIf { it != details.info.name },
            model = model.trim().takeIf { it != s.model || provider != s.provider },
            provider = provider.takeIf { it != s.provider },
            reasoning = reasoning.takeIf { it != s.reasoning },
            approvals = approvals.takeIf { it != s.approvals },
            identity = identity.takeIf { it != details.identity },
            soul = soul.takeIf { it != details.soul },
        )
    }

    /** An external Agent's registration with this draft's name and working folder; null when neither changed. */
    fun registration(details: AgentDetails): AgentRegistration? {
        val info = details.info
        val connectionId = info.connectionId?.takeIf { kind != ExecutionKind.Native } ?: return null
        val folder = if (kind == ExecutionKind.Acp) cwd.trim() else details.cwd
        if (name.trim() == info.name && folder == details.cwd) return null
        return AgentRegistration(info.id, name.trim(), connectionId, info.avatar, folder, details.remoteAgentId)
    }

    /** Why it can't be saved: a name that's missing, too long or another Agent's, no model, or an ACP Agent's working folder. */
    fun problems(agentId: String, agents: List<AgentInfo>): List<String> = buildList {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) add("Name is required")
        else if (trimmed.length > MaxName) add("Name is at most $MaxName characters")
        else if (agents.any { it.id != agentId && it.name.trim().equals(trimmed, ignoreCase = true) }) add("Another Agent is called $trimmed")
        when (kind) {
            ExecutionKind.Native -> if (model.isBlank()) add("Pick a model")
            ExecutionKind.Acp -> cwdProblem(cwd)?.let(::add)
            ExecutionKind.Remote -> Unit
        }
    }

    companion object {
        const val MaxName = 40
        fun of(d: AgentDetails) = AgentDraft(d.info.name, d.identity, d.soul, d.settings.provider, d.settings.model, d.settings.reasoning, d.settings.approvals,
            d.cwd.orEmpty(), d.info.executionKind)
    }
}

/** What would end the inspector with unsaved changes, until the user saves, discards or keeps editing. */
sealed interface InspectorExit {
    data object Close : InspectorExit
    data class Inspect(val agentId: String) : InspectorExit
    /** The host leaves, e.g. to another Server or the full Settings; it runs its own action once [InspectorState.left]. */
    data object Leave : InspectorExit
}

enum class GuardChoice { Save, Discard, Keep }

/**
 * The Agent settings beside the open Session, apart from it: [agentId] is the inspected Agent while open, [pages] the
 * levels opened above its settings, [draft] the one set of unsaved edits to [details], and [guard] the exit waiting on
 * the user because of them. [left] is set once a guarded [InspectorExit.Leave] may go ahead.
 */
data class InspectorState(
    val agentId: String? = null,
    val pages: List<InspectorPage> = emptyList(),
    val details: AgentDetails? = null,
    val draft: AgentDraft? = null,
    val models: ModelsCatalog = ModelsCatalog(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val guard: InspectorExit? = null,
    val left: Boolean = false,
    val errors: List<String> = emptyList(),
    /** An external Agent's Harness connection, and whether it's being checked. */
    val connection: HarnessConnectionStatus? = null,
    val checking: Boolean = false,
) {
    val open get() = agentId != null
    val page get() = pages.lastOrNull() ?: InspectorPage.Agent
    val changes get() = details?.let { d -> draft?.changes(d) }?.takeIf { it != UpdateAgentParams(it.agentId) }
    val registration get() = details?.let { d -> draft?.registration(d) }
    val dirty get() = changes != null || registration != null
}

/** Opens, edits and saves one Agent's defaults; nothing unsaved is dropped without the user choosing to. */
class AgentInspectorViewModel(private val config: ConfigRepository) : ViewModel() {
    val state: StateFlow<InspectorState>
        field = MutableStateFlow(InspectorState())
    private var watching: Job? = null
    private val harnesses get() = config.connection.harnesses

    init {
        config.connection.bind(viewModelScope)
        viewModelScope.launch {
            harnesses.state.collect { h ->
                state.update { s -> s.copy(connection = h.connections?.firstOrNull { it.config.id == s.details?.info?.connectionId },
                    checking = s.details?.info?.connectionId in h.checking) }
            }
        }
    }

    /** Checks the inspected external Agent's harness again. */
    fun check() {
        val id = state.value.details?.info?.connectionId ?: return
        viewModelScope.launch { catching { config.connection.run { harnesses.check(id) } }.onFailure { e -> state.update { it.copy(errors = listOf(e.message ?: e.toString())) } } }
    }

    /** Shows [agentId]; false while unsaved edits to another Agent wait on the user. */
    fun inspect(agentId: String): Boolean {
        val s = state.value
        if (s.agentId == agentId) return true
        if (s.dirty) return guard(InspectorExit.Inspect(agentId))
        open(agentId)
        return true
    }

    /** Closes; false while unsaved edits wait on the user. */
    fun close(): Boolean = if (state.value.dirty) guard(InspectorExit.Close) else true.also { reset() }

    /** Before the host leaves the inspector behind; true when it may, otherwise once [InspectorState.left]. */
    fun leave(): Boolean = if (!state.value.open || !state.value.dirty) true.also { reset() } else guard(InspectorExit.Leave)

    /** Back: up a level, or closing from the Agent's settings. */
    fun back(): Boolean {
        if (state.value.pages.isNotEmpty()) { state.update { it.copy(pages = it.pages.dropLast(1)) }; return true }
        return close()
    }

    fun push(page: InspectorPage) = state.update { if (it.page == page) it else it.copy(pages = it.pages + page) }

    fun edit(change: (AgentDraft) -> AgentDraft) = state.update { s -> s.copy(draft = s.draft?.let(change), saved = false) }

    /** Saves the draft as [agents]' validation allows, then runs [then]. */
    fun save(agents: List<AgentInfo>, then: () -> Unit = {}) {
        val s = state.value
        val details = s.details ?: return
        val draft = s.draft ?: return
        val problems = draft.problems(details.info.id, agents)
        if (problems.isNotEmpty()) return state.update { it.copy(errors = problems) }
        val changes = s.changes
        val registration = s.registration
        if (changes == null && registration == null) return then()
        state.update { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            try {
                config.connection.run {
                    if (registration != null) { harnesses.register(registration); config.loadDetails(details.info.id).join() }
                    else config.updateAgent(changes!!)
                }
                state.update { it.copy(saving = false, saved = true, draft = it.details?.let(AgentDraft::of)) }
                then()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(saving = false, errors = listOf(e.message ?: e.toString())) }
            }
        }
    }

    fun resolve(choice: GuardChoice, agents: List<AgentInfo>) {
        val exit = state.value.guard ?: return
        when (choice) {
            GuardChoice.Keep -> state.update { it.copy(guard = null) }
            GuardChoice.Discard -> proceed(exit)
            GuardChoice.Save -> save(agents) { proceed(exit) }
        }
    }

    /** The host ran its action after a guarded [leave]. */
    fun leaveHandled() = state.update { it.copy(left = false) }

    fun loadModels(refresh: Boolean = false) = viewModelScope.launch {
        catching { config.connection.run { config.models(refresh) } }.onSuccess { m -> state.update { it.copy(models = m) } }
    }

    private fun guard(exit: InspectorExit): Boolean {
        state.update { it.copy(guard = exit, errors = emptyList()) }
        return false
    }

    private fun proceed(exit: InspectorExit) = when (exit) {
        InspectorExit.Close -> reset()
        is InspectorExit.Inspect -> open(exit.agentId)
        InspectorExit.Leave -> { reset(); state.update { it.copy(left = true) } }
    }

    private fun open(agentId: String) {
        watching?.cancel()
        state.value = InspectorState(agentId, models = state.value.models)
        config.load(agentId)
        if (state.value.models.providers.isEmpty()) loadModels()
        watching = viewModelScope.launch {
            config.state.map { it[agentId]?.details }.filterNotNull().distinctUntilChanged().collect { d ->
                state.update { s ->
                    // Unedited, the draft follows the saved settings, e.g. after a save elsewhere.
                    val unedited = s.draft == null || s.details?.let(AgentDraft::of) == s.draft
                    s.copy(details = d, draft = if (unedited) AgentDraft.of(d) else s.draft,
                        connection = harnesses.state.value.connections?.firstOrNull { it.config.id == d.info.connectionId })
                }
            }
        }
    }

    private fun reset() {
        watching?.cancel()
        state.update { InspectorState(models = it.models) }
    }
}
