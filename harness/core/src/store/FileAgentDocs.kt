package kinuvo.harness.core.store

import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.AgentDocs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Agent documents as Markdown files in the Agent's folder. */
class FileAgentDocs(private val files: AgentFiles) : AgentDocs {
    private val lock = Mutex()

    override suspend fun read(agentId: String, doc: AgentDoc): String = files.doc(agentId, doc).readTextOrNull().orEmpty()

    override suspend fun write(agentId: String, doc: AgentDoc, text: String) = lock.withLock { writeAtomically(files.doc(agentId, doc), text) }

    override suspend fun update(agentId: String, doc: AgentDoc, transform: (String) -> String): String = lock.withLock {
        transform(read(agentId, doc)).also { writeAtomically(files.doc(agentId, doc), it) }
    }
}
