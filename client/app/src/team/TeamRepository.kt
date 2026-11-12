package kinuvo.app.team

import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.catching
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.team.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TeamRepository(internal val connection: ConnectedServer) {
    val state: StateFlow<Map<TeamId, TeamSnapshot>>
        field = MutableStateFlow(emptyMap())
    val error: StateFlow<String?>
        field = MutableStateFlow(null)
    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        attached?.cancel()
        attached = connection.scope.launch {
            launch { client.events(Api.TeamChanged).collect { change ->
                val snapshot = change.snapshot
                state.update { if (snapshot == null) it - change.id else it + (change.id to snapshot) }
            } }
            client.state.collect { if (it is ConnectionState.Connected) refresh() }
        }
    }

    internal fun detach() { attached?.cancel(); state.value = emptyMap(); error.value = null }

    suspend fun refresh() {
        catching { connection.call { it.call(Api.Teams, Unit) } }
            .onSuccess { teams -> state.value = teams.associateBy { it.team.id }; error.value = null }
            .onFailure { error.value = it.message }
    }

    suspend fun load(id: TeamId) = connection.call { it.call(Api.Team, id) }.also { team -> state.update { it + (id to team) } }
    suspend fun create(spec: TeamSpec) = connection.call { it.call(Api.CreateTeam, spec) }.also { team -> state.update { it + (team.team.id to team) } }
    suspend fun update(id: TeamId, spec: TeamSpec) = connection.call { it.call(Api.UpdateTeam, UpdateTeamParams(id, spec)) }
    suspend fun delete(id: TeamId) = connection.call { it.call(Api.DeleteTeam, id) }
    suspend fun send(id: TeamId, text: String) = connection.call { it.call(Api.TeamSend, TeamSendParams(id, text)) }
    suspend fun stop(id: TeamId) = connection.call { it.call(Api.TeamStop, id) }
    suspend fun respond(id: TeamId, request: InteractionId, answer: InteractionAnswer) =
        connection.call { it.call(Api.TeamRespond, TeamRespondParams(id, request, answer)) }
}
