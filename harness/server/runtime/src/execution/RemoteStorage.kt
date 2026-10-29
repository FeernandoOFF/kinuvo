package kinuvo.harness.server.execution

import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.ProtocolJson
import kotlinx.io.files.Path

fun remoteLinks(home: Path): RemoteLinks {
    val file = Path(home, "execution-links-private.json")
    val initial = file.readTextOrNull()?.let { ProtocolJson.decodeFromString(RemoteSecrets.serializer(), it) } ?: RemoteSecrets()
    return RemoteLinks(initial) { writeAtomically(file, ProtocolJson.encodeToString(RemoteSecrets.serializer(), it), private = true) }
}

class ExecutionStorage(private val home: Path) {
    private val catalogFile = Path(home, "execution-catalog.json")
    private val sessionsFile = Path(home, "execution-sessions.json")
    private val sessionSerializer = kotlinx.serialization.builtins.ListSerializer(ExternalSession.serializer())
    val registry = ExecutionRegistry(catalogFile.readTextOrNull()?.let { ProtocolJson.decodeFromString(kinuvo.protocol.ExecutionCatalog.serializer(), it) }
        ?: kinuvo.protocol.ExecutionCatalog(emptyList(), emptyList())) {
        writeAtomically(catalogFile, ProtocolJson.encodeToString(kinuvo.protocol.ExecutionCatalog.serializer(), it))
    }
    fun sessions(): List<ExternalSession> = sessionsFile.readTextOrNull()?.let { ProtocolJson.decodeFromString(sessionSerializer, it) } ?: emptyList()
    fun saveSessions(sessions: List<ExternalSession>) = writeAtomically(sessionsFile, ProtocolJson.encodeToString(sessionSerializer, sessions))
}
