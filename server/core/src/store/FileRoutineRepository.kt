package kinuvo.server.store

import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import kinuvo.server.routines.RoutineRepository
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Routines in each Agent's `routines.json`. */
class FileRoutineRepository(private val files: AgentFiles) : RoutineRepository {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val serializer = ListSerializer(Routine.serializer())

    override suspend fun get(agentId: AgentId): List<Routine> =
        files.routines(agentId).readTextOrNull()?.let { json.decodeFromString(serializer, it) }.orEmpty()

    override suspend fun update(agentId: AgentId, transform: (List<Routine>) -> List<Routine>): List<Routine> = lock.withLock {
        transform(get(agentId)).also { writeAtomically(files.routines(agentId), json.encodeToString(serializer, it)) }
    }
}
