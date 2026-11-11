package kinuvo.server.store

import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import kinuvo.harness.AgentDoc
import kinuvo.harness.AgentDocs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kinuvo.protocol.agent.AgentId

/** Agent documents as Markdown files in the Agent's folder. */
class FileAgentDocs(private val files: AgentFiles) : AgentDocs {
    private val lock = Mutex()

    override suspend fun read(agentId: AgentId, doc: AgentDoc): String = files.doc(agentId, doc).readTextOrNull().orEmpty()

    override suspend fun write(agentId: AgentId, doc: AgentDoc, text: String) = lock.withLock { writeAtomically(files.doc(agentId, doc), text) }

    override suspend fun update(agentId: AgentId, doc: AgentDoc, transform: (String) -> String): String = lock.withLock {
        transform(read(agentId, doc)).also { writeAtomically(files.doc(agentId, doc), it) }
    }
}
