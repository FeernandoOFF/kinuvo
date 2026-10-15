package kinuvo.harness.core.store

import kinuvo.harness.core.routines.RoutineRepository
import kinuvo.protocol.Routine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Routines in each Agent's `routines.json`. */
class FileRoutineRepository(private val files: AgentFiles) : RoutineRepository {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val serializer = ListSerializer(Routine.serializer())

    override suspend fun get(agentId: String): List<Routine> =
        files.routines(agentId).readTextOrNull()?.let { json.decodeFromString(serializer, it) }.orEmpty()

    override suspend fun update(agentId: String, transform: (List<Routine>) -> List<Routine>): List<Routine> = lock.withLock {
        transform(get(agentId)).also { writeAtomically(files.routines(agentId), json.encodeToString(serializer, it)) }
    }
}
