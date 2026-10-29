@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.execution.acp

import com.agentclientprotocol.client.v2.ClientSession
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import kinuvo.execution.api.ExecutionSession
import kinuvo.execution.api.ExecutionState
import kinuvo.execution.api.ExecutionBroker
import kinuvo.protocol.ExecutionCapabilities
import kinuvo.protocol.ExecutionPhase
import kotlinx.serialization.json.JsonElement

/** Collect updates separately from prompt: the SDK's prompt result only acknowledges acceptance. */
class AcpSession(
    private val session: ClientSession,
    private val state: ExecutionState,
    private val broker: ExecutionBroker,
    override val capabilities: ExecutionCapabilities,
) : ExecutionSession {
    override suspend fun prompt(text: String) {
        session.prompt(listOf(ContentBlock.Text(text)))
        state.accepted()
    }

    override suspend fun cancel() { session.cancel() }

    override suspend fun answer(requestId: String, answer: JsonElement) {
        check(broker.answer(requestId, answer)) { "Request is no longer pending" }
    }

    suspend fun collect(onUpdate: suspend (SessionUpdate) -> Unit) {
        session.updates.collect { received ->
            if (state.snapshot().phase == ExecutionPhase.Lost) return@collect
            val update = received.update
            when (val phase = (update as? SessionUpdate.StateUpdate)?.state) {
                is StateUpdate.Running -> state.update(ExecutionPhase.Running)
                is StateUpdate.RequiresAction -> state.update(ExecutionPhase.RequiresAction)
                is StateUpdate.Idle -> state.update(ExecutionPhase.Idle, phase.stopReason?.toString())
                else -> Unit
            }
            onUpdate(update)
        }
    }
}
