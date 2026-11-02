package kinuvo.app.routine

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The connected Server's Routines for every Agent shown, kept current from its events; finished runs become [Notice]s. */
class RoutineRepository(private val notifications: Notifications, internal val connection: ConnectedServer) {
    /** Routines by Agent ID. */
    val state: StateFlow<Map<String, List<Routine>>>
        field = MutableStateFlow(emptyMap())

    val errors: StateFlow<Map<String, String>>
        field = MutableStateFlow(emptyMap())

    private val requested = mutableSetOf<String>()
    private val loads = mutableMapOf<String, Job>()

    private val scope get() = connection.scope
    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        clear()
        attached = scope.launch {
            launch { client.events(Api.RoutinesChanged).collect { c -> state.update { it + (c.agentId to c.routines) } } }
            launch { client.events(Api.RoutineRan).collect { notifications.post(Notice.RoutineRan(it.agentId, it.routine, it.sessionId, it.error)) } }
            client.state.collect { if (it is ConnectionState.Connected) requested.toList().forEach(::load) }
        }
    }

    internal fun detach() { clear() }

    fun load(agentId: String) {
        requested += agentId
        loads[agentId]?.cancel()
        loads[agentId] = scope.launch {
            errors.update { it - agentId }
            catching {
                withTimeoutOrNull(15_000) { connection.call { it.call(Api.Routines, AgentRef(agentId)) } }
                    ?: error("The Server took too long to respond. Try again.")
            }.onSuccess { routines ->
                state.update { it + (agentId to routines) }
            }.onFailure { error ->
                errors.update { it + (agentId to (error.message ?: "The Server couldn't load routines.")) }
            }
        }
    }

    suspend fun create(agentId: String, spec: RoutineSpec) = call { it.call(Api.CreateRoutine, CreateRoutineParams(agentId, spec)) }

    suspend fun update(agentId: String, routineId: String, spec: RoutineSpec) = call { it.call(Api.UpdateRoutine, UpdateRoutineParams(agentId, routineId, spec)) }

    suspend fun run(agentId: String, routineId: String) = call { it.call(Api.RunRoutine, RoutineParams(agentId, routineId)) }

    suspend fun remove(agentId: String, routineId: String) = call { it.call(Api.RemoveRoutine, RoutineParams(agentId, routineId)) }

    /** The newest Sessions [routineId] ran in. */
    suspend fun runs(agentId: String, routineId: String): List<SessionInfo> =
        call { it.call(Api.Sessions, SessionsParams(agentId, limit = 20, routineId = routineId)).sessions }

    private suspend fun <T> call(block: suspend (KinuvoClient) -> T): T =
        connection.call(block)

    private fun clear() {
        attached?.cancel()
        loads.values.forEach { it.cancel() }
        loads.clear()
        requested.clear()
        errors.value = emptyMap()
        state.value = emptyMap()
    }
}
