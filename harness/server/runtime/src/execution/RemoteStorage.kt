package kinuvo.harness.server.execution

import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.*
import kinuvo.harness.core.store.children
import kinuvo.harness.core.store.exists
import kinuvo.harness.core.store.isDirectory
import kinuvo.harness.core.store.isInside
import kinuvo.harness.server.SessionLog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.io.files.Path

fun remoteLinks(home: Path): RemoteLinks {
    val file = Path(home, "execution-links-private.json")
    val initial = file.readTextOrNull()?.let { ProtocolJson.decodeFromString(RemoteSecrets.serializer(), it) } ?: RemoteSecrets()
    return RemoteLinks(initial) { writeAtomically(file, ProtocolJson.encodeToString(RemoteSecrets.serializer(), it), private = true) }
}

class ExecutionStorage(private val home: Path) : ExecutionRecoveryStore {
    private val catalogFile = Path(home, "execution-catalog.json")
    private val sessionsFile = Path(home, "execution-sessions.json")
    private val sessionSerializer = ListSerializer(ExternalSession.serializer())
    val registry = ExecutionRegistry(catalogFile.readTextOrNull()?.let { ProtocolJson.decodeFromString(ExecutionCatalog.serializer(), it) }
        ?: ExecutionCatalog(emptyList(), emptyList())) {
        writeAtomically(catalogFile, ProtocolJson.encodeToString(ExecutionCatalog.serializer(), it))
    }
    private fun path(agentId: String, sessionId: String): Path {
        require(Regex("[a-z0-9][a-z0-9-]{0,31}").matches(agentId)) { "Invalid external Agent ID" }
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(sessionId)) { "Invalid Session ID" }
        val root = Path(home, "agents")
        val result = Path(root, agentId, "sessions", "$sessionId.execution.json")
        require(isInside(root, result)) { "Execution recovery path escapes storage" }
        return result
    }
    var loadErrors: List<String> = emptyList()
        private set
    override fun load(): List<ExecutionRecovery> {
        val errors = mutableListOf<String>()
        val records = mutableListOf<ExecutionRecovery>()
        val conflicts = mutableSetOf<String>()
        Path(home, "agents").children().filter { it.isDirectory() }.forEach { owner ->
            Path(owner, "sessions").children().filter { it.name.endsWith(".execution.json") }.forEach { file ->
                val localId = file.name.removeSuffix(".execution.json")
                var claimedId: String? = null
                try {
                    val record = ProtocolJson.decodeFromString(ExecutionRecovery.serializer(), requireNotNull(file.readTextOrNull()))
                    claimedId = record.sessionId
                    if (path(record.agentId, record.sessionId) != file) {
                        conflicts += record.sessionId
                        error("Recovery identity mismatch")
                    }
                    records += record
                } catch (e: Exception) {
                    conflicts += localId
                    claimedId?.let { conflicts += it }
                    errors += "$file: ${e.message}"
                }
            }
        }
        records.groupBy { it.sessionId }.filterValues { it.size > 1 }.keys.forEach {
            conflicts += it
            errors += "Duplicate execution Session identity: $it"
        }
        loadErrors = errors.toList()
        errors.forEach { KotlinLogging.logger("kinuvo.execution").error { "Execution recovery sidecar rejected: $it" } }
        return records.filter { it.sessionId !in conflicts }
    }
    override fun save(record: ExecutionRecovery) = writeAtomically(path(record.agentId, record.sessionId), ProtocolJson.encodeToString(ExecutionRecovery.serializer(), record), private = true)
    override fun delete(agentId: String, sessionId: String) { SystemFileSystem.delete(path(agentId, sessionId), mustExist = false) }

    /** Legacy blob stays read-only; each destination sidecar identifies its own import. */
    suspend fun migrate(log: SessionLog): List<String> {
        val source = sessionsFile.readTextOrNull() ?: return emptyList()
        val backup = Path(home, "execution-sessions.legacy.json")
        if (!backup.exists()) writeAtomically(backup, source, private = true)
        if (backup.readTextOrNull() != source) return listOf("Legacy execution snapshot changed after its backup; migration stopped")
        val receiptFile = Path(home, "execution-migration.json")
        val receiptSerializer = ListSerializer(String.serializer())
        val completed = receiptFile.readTextOrNull()?.let { ProtocolJson.decodeFromString(receiptSerializer, it) }?.toMutableSet() ?: mutableSetOf()
        val failures = mutableListOf<String>()
        val array = ProtocolJson.parseToJsonElement(source).jsonArray
        for ((index, value) in array.withIndex()) {
            try {
                val old = ProtocolJson.decodeFromJsonElement(ExternalSession.serializer(), value)
                val id = old.snapshot.sessionId
                val destination = path(old.agentId, id)
                val identity = "legacy:$index:${old.agentId}:$id"
                if (identity in completed) continue
                val existing = destination.readTextOrNull()?.let { ProtocolJson.decodeFromString(ExecutionRecovery.serializer(), it) }
                require(existing == null || existing.migrationId == identity) { "Recovery destination belongs to another Session" }
                require(log.agents().none { it.id == old.agentId }) { "Native owner collision" }
                val registration = registry.catalog().agents.firstOrNull { it.id == old.agentId } ?: error("Missing legacy registration")
                val record = existing ?: ExecutionRecovery(old.agentId, id, registry.configured(old.agentId), registration,
                    "migration:$index", old.snapshot.externalSessionId, lost = true, archived = registration.archived, legacyOrder = (array.size - index - 1).toLong(), migrationId = identity,
                    checkpoint = transcript(old.snapshot).mapIndexed { seq, entry -> if (entry is SessionEntry.UserMessage) entry.copy(submissionId = "migration:$index:$seq") else entry })
                val transcriptFile = Path(home, "agents", old.agentId, "sessions", "$id.jsonl")
                if (existing == null) {
                    require(!transcriptFile.exists()) { "Transcript destination already exists" }
                    require(Path(home, "agents").children().none { Path(it, "sessions", "$id.jsonl").exists() }) { "Session ID collision" }
                    save(record)
                }
                if (!transcriptFile.exists()) writeAtomically(transcriptFile, ProtocolJson.encodeToString(SessionEntry.serializer(), SessionEntry.Started("")) + "\n")
                log.appendAll(old.agentId, id, record.checkpoint)
                save(record.copy(checkpoint = emptyList()))
                completed += identity
                writeAtomically(receiptFile, ProtocolJson.encodeToString(receiptSerializer, completed.sorted()), private = true)
            } catch (e: Exception) { failures += "Legacy record $index: ${e.message}" }
        }
        return failures
    }
}
