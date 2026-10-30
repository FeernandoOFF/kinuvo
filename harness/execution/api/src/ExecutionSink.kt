package kinuvo.execution.api

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.AttachmentData
import kinuvo.protocol.SessionEntry

/** Ordered SessionLog boundary. Commit appends an identity-reconciled batch; it never upserts saved entries. */
interface ExecutionSink {
    suspend fun publish(event: AgentEvent)
    suspend fun commit(entries: List<SessionEntry>)
}

data class ExecutionInput(val text: String, val attachments: List<AttachmentData> = emptyList())

/** Source insertion identity; null means unavailable, never rejection or foreground completion. */
data class ExecutionAcceptance(val messageId: String? = null)
