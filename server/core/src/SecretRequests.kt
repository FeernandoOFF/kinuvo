package kinuvo.server

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.secret.REFERENCE
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId

/**
 * Pending `request_secret` cards. The user picks a password manager reference, types a value that [Secrets.session]
 * keeps for the asking Agent and Session, or declines; the Agent only ever gets a reference.
 */
class SecretRequests(private val secrets: Secrets) {
    private val broker = Broker<InteractionAnswer.Secret>()

    /** Returns false when nothing is waiting under [id] or it was already answered. */
    suspend fun respond(id: InteractionId, answer: InteractionAnswer.Secret): Boolean {
        require(answer.reference == null || answer.value == null) { "Pick a reference or type a value, not both" }
        require(answer.value?.isNotEmpty() != false) { "Type a value or decline" }
        answer.reference?.let { reference ->
            val scheme = REFERENCE.matchEntire(reference.trim())?.groupValues?.get(3).orEmpty().substringBefore("://", "")
            require(scheme != SessionSecrets.SCHEME && secrets.providers.any { it.scheme == scheme && it.status().connected }) {
                "Pick a field in a connected password manager"
            }
        }
        return broker.respond(id, answer)
    }

    /** Asks for [label] in [sessionId] of [agentId] through [emit] and waits; returns what the Agent sees. */
    suspend fun ask(agentId: AgentId, sessionId: SessionId, toolCallId: ToolCallId, label: String, reason: String, emit: suspend (AgentEvent) -> Unit): String {
        val pending = broker.register()
        try {
            emit(AgentEvent.InteractionRequested(PendingInteraction.Secret(pending.id, toolCallId, label, reason)))
            val answer = pending.await()
            emit(AgentEvent.InteractionResolved(pending.id))
            return answer.reference?.trim() ?: answer.value?.let { secrets.session.put(agentId, sessionId, it) }
                ?: "The user declined. Don't ask for this credential in chat; continue without it or stop."
        } finally {
            pending.close()
        }
    }
}
