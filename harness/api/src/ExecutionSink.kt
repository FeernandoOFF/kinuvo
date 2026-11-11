package kinuvo.harness

import kinuvo.protocol.surface.SurfaceAction

import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.AttachmentData
import kinuvo.protocol.session.SessionEntry

/** Ordered SessionLog boundary. Commit appends an identity-reconciled batch; it never upserts saved entries. */
interface ExecutionSink {
    suspend fun publish(event: AgentEvent)
    suspend fun commit(entries: List<SessionEntry>)
}

data class ExecutionInput(
    val text: String,
    val attachments: List<AttachmentData> = emptyList(),
    val action: SurfaceAction? = null,
    val kind: RunKind = RunKind.Chat(),
    val runId: String? = null,
)

/** Source insertion identity; null means unavailable, never rejection or foreground completion. */
data class ExecutionAcceptance(val messageId: String? = null)
