package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.approval.*
import kinuvo.protocol.session.*
import kinuvo.protocol.team.*
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.execution.AgentExecution
import kinuvo.server.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

sealed class TeamError(override val code: String, override val message: String) : ServerError {
    data class Missing(val id: TeamId) : TeamError(WireErrorCode.NotFound, "No Team '$id'")
    data object Busy : TeamError(WireErrorCode.Conflict, "The Team is running")
}

class Teams internal constructor(
    private val harness: HarnessRuntime,
    private val dispatch: AgentExecution,
    private val scope: CoroutineScope,
    private val join: suspend (SessionId) -> Unit,
    private val agents: suspend () -> List<AgentInfo>,
    private val changed: (TeamChanged) -> Unit,
) {
    private class Turn(val team: TeamId, val root: SessionId) {
        var job: Job? = null
        var member: TeamMember? = null
        var done = CompletableDeferred<Unit>()
        var handoff: AgentId? = null
        var failure: String? = null
        var hops = 0
        val pending = mutableMapOf<InteractionId, TeamInteraction>()
    }
    private val lock = Mutex()
    private val turns = mutableMapOf<TeamId, Turn>()
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        harness.subAgents?.onBudgetExhausted = { root -> lock.withLock { turns.values.firstOrNull { it.root == root }?.job?.cancel() } }
        harness.log.onEvent { _, session, event -> events.trySend { relay(session, event) } }
        scope.launch { for (event in events) event() }
    }

    private suspend fun relay(session: SessionId, event: AgentEvent) = lock.withLock {
        val turn = turns.values.firstOrNull { it.member?.sessionId == session } ?: return@withLock
        val member = requireNotNull(turn.member)
        when (event) {
            is AgentEvent.InteractionRequested -> turn.pending[event.interaction.id] = TeamInteraction(member.agentId, event.childSessionId ?: session, event.interaction)
            is AgentEvent.InteractionResolved -> turn.pending.remove(event.id)
            is AgentEvent.RunFinished -> turn.done.complete(Unit)
            is AgentEvent.RunError -> { turn.failure = event.message; turn.done.complete(Unit) }
            is AgentEvent.ExecutionPhaseChanged -> if (event.phase == ExecutionPhase.Lost) {
                turn.failure = "Member harness connection lost"; turn.done.complete(Unit)
            } else return@withLock
            else -> return@withLock
        }
        throwing<TeamError, _> { publish(turn.team) }
    }

    context(_: Raise<TeamError>)
    private suspend fun find(id: TeamId): Team = harness.store.teams().firstOrNull { it.id == id } ?: raise(TeamError.Missing(id))
    private fun snapshot(team: Team) = TeamSnapshot(team, turns[team.id]?.member?.agentId, turns[team.id]?.pending?.values?.toList().orEmpty())
    context(_: Raise<TeamError>)
    private suspend fun publish(id: TeamId) { changed(TeamChanged(id, snapshot(find(id)))) }
    suspend fun list() = lock.withLock { harness.store.teams().map(::snapshot) }
    context(_: Raise<TeamError>)
    suspend fun get(id: TeamId) = lock.withLock { snapshot(find(id)) }

    private suspend fun validate(spec: TeamSpec) {
        require(spec.name.isNotBlank() && spec.name.length <= 120)
        require(spec.members.isNotEmpty() && spec.members.size <= 16 && spec.members.distinct() == spec.members)
        require(spec.defaultMember == null || spec.defaultMember in spec.members)
        val agents = agents().map { it.id }
        spec.members.forEach { require(it in agents) { "Unknown Agent '$it'" } }
    }

    context(_: Raise<TeamError>)
    suspend fun create(spec: TeamSpec): TeamSnapshot = lock.withLock {
        validate(spec)
        val members = spec.members.map { TeamMember(it, dispatch.create(it).id) }
        val team = Team(TeamId(Uuid.random().toString()), spec.name.trim(), members, spec.defaultMember ?: spec.members.first())
        members.forEach { harness.store.append(it.agentId, it.sessionId, SessionEntry.Details(teamId = team.id)) }
        harness.store.saveTeam(team)
        publish(team.id)
        snapshot(team)
    }

    context(_: Raise<TeamError>)
    suspend fun update(p: UpdateTeamParams): TeamSnapshot = lock.withLock {
        if (p.id in turns) raise(TeamError.Busy)
        validate(p.spec)
        val before = find(p.id)
        val members = p.spec.members.map { id -> before.members.firstOrNull { it.agentId == id } ?: TeamMember(id, dispatch.create(id).id).also {
            harness.store.append(id, it.sessionId, SessionEntry.Details(teamId = p.id))
        } }
        val team = before.copy(name = p.spec.name.trim(), members = members, defaultMember = p.spec.defaultMember ?: members.first().agentId)
        harness.store.saveTeam(team); publish(p.id); snapshot(team)
    }

    context(_: Raise<TeamError>)
    suspend fun delete(id: TeamId) = lock.withLock {
        if (id in turns) raise(TeamError.Busy)
        find(id)
        harness.store.deleteTeam(id)
        changed(TeamChanged(id))
    }

    context(_: Raise<TeamError>)
    private suspend fun append(id: TeamId, kind: TeamEntryKind, text: String, agent: AgentId? = null, target: AgentId? = null) {
        val team = find(id)
        harness.store.saveTeam(team.copy(log = team.log + TeamEntry(team.log.size, kind, text, agent, target)))
        publish(id)
    }

    private suspend fun mentions(team: Team, text: String): List<AgentId> {
        val agents = agents().filter { a -> team.members.any { it.agentId == a.id } }
        return Regex("(?<![\\w@])@([\\w-]+)").findAll(text).mapNotNull { match ->
            val token = match.groupValues[1]
            agents.firstOrNull { it.id.value.equals(token, true) }?.id
                ?: agents.singleOrNull { it.name.equals(token, true) }?.id
        }.distinct().toList().ifEmpty { listOf(team.defaultMember) }
    }

    context(_: Raise<TeamError>)
    suspend fun send(p: TeamSendParams) = lock.withLock {
        require(p.text.isNotBlank())
        if (p.id in turns) raise(TeamError.Busy)
        val team = find(p.id)
        val targets = mentions(team, p.text)
        val turn = Turn(p.id, SessionId(Uuid.random().toString()))
        append(p.id, TeamEntryKind.User, p.text)
        turn.member = team.members.first { it.agentId == targets.first() }
        turns[p.id] = turn
        publish(p.id)
        turn.job = scope.launch(start = CoroutineStart.LAZY) { throwing<TeamError, _> {
            try {
                for (target in targets) {
                    var next: AgentId? = target
                    while (next != null) {
                        val member = lock.withLock {
                            find(p.id).members.first { it.agentId == next }.also {
                                turn.member = it; turn.done = CompletableDeferred(); turn.handoff = null; turn.failure = null
                                harness.subAgents?.shareBudget(it.sessionId, turn.root)
                                publish(p.id)
                            }
                        }
                        val team = find(p.id)
                        val context = team.log.drop(member.cursor).joinToString("\n") { "[${it.kind} ${it.agentId ?: "user"}${it.target?.let { " -> $it" }.orEmpty()}] ${it.text}" }
                        dispatch.send(SendParams(member.agentId, member.sessionId, "[Team ${team.name}]\n$context"))
                        turn.done.await()
                        join(member.sessionId)
                        val barrier = CompletableDeferred<Unit>()
                        events.send { barrier.complete(Unit) }
                        barrier.await()
                        turn.failure?.let { error(it) }
                        lock.withLock {
                            val result = harness.store.entries(member.agentId, member.sessionId).takeLastWhile { it !is SessionEntry.UserMessage }.filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text
                            if (result != null) append(p.id, TeamEntryKind.Reply, result, member.agentId)
                            val latest = find(p.id)
                            harness.store.saveTeam(latest.copy(members = latest.members.map { if (it.agentId == member.agentId) it.copy(cursor = latest.log.size) else it }))
                            next = turn.handoff
                            turn.pending.clear()
                            publish(p.id)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { lock.withLock { append(p.id, TeamEntryKind.Error, e.message ?: "Team turn failed") } }
            finally { withContext(NonCancellable) {
                find(p.id).members.forEach { dispatch.stop(it.sessionId); join(it.sessionId) }
                lock.withLock {
                    find(p.id).members.forEach { harness.subAgents?.releaseBudget(it.sessionId) }
                    turns.remove(p.id); publish(p.id)
                }
            } }
        } }
        turn.job?.start()
    }

    suspend fun finished(session: SessionId, cancelled: Boolean) = lock.withLock {
        turns.values.firstOrNull { it.member?.sessionId == session }?.let {
            if (cancelled) it.job?.cancel() else it.done.complete(Unit)
        }
    }

    context(_: Raise<TeamError>)
    suspend fun stop(id: TeamId) { lock.withLock { find(id); turns[id]?.job?.cancel() } }
    context(_: Raise<TeamError>)
    suspend fun respond(p: TeamRespondParams): Boolean {
        val pending = lock.withLock { find(p.id); turns[p.id]?.pending?.get(p.requestId) } ?: return false
        return dispatch.answer(RespondParams(pending.sessionId, p.requestId, p.answer))
    }

    suspend fun tools(agent: AgentId, session: SessionId): List<Tool> = lock.withLock {
        if (turns.values.none { it.member?.sessionId == session }) return@withLock emptyList()
        listOf(object : CoreTool {
            override val descriptor = toolSpec(ToolNames.TEAM_HANDOFF, "Hand the Team conversation to another member after your reply. At most four handoffs per user message.", requiredParameters = listOf(ToolParameter("agent_id", "Team member Agent id", ParameterType.String), ToolParameter("note", "Context for the next member", ParameterType.String)))
            override suspend fun execute(args: JsonObject): String = throwing<TeamError, _> { lock.withLock {
                val turn = turns.values.firstOrNull { it.member?.sessionId == session } ?: error("No active Team turn")
                val target = AgentId(args.requireString("agent_id"))
                require(target != agent && find(turn.team).members.any { it.agentId == target }) { "Choose another Team member" }
                require(turn.hops < 4 && turn.handoff == null) { "Handoff limit reached" }
                val note = args.requireString("note"); require(note.isNotBlank())
                turn.hops++; turn.handoff = target
                append(turn.team, TeamEntryKind.Handoff, note, agent, target)
                "Handoff queued"
            } }
        })
    }
}
