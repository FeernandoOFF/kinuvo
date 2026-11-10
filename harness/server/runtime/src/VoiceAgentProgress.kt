package kinuvo.harness.server

import kinuvo.server.plugin.AgentProgress
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ExecutionSnapshot
import kinuvo.protocol.PendingInteraction

/** A native run's progress; a message is reported only once more work follows it, since the last one is the reply. */
internal class NativeVoiceProgress {
    private var text: String? = null

    fun update(event: AgentEvent): List<AgentProgress> = buildList {
        fun flush() { text?.let { add(AgentProgress.Text(it)) }; text = null }
        when (event) {
            is AgentEvent.TextMessageEnd -> { flush(); text = event.text.takeIf { it.isNotBlank() } }
            is AgentEvent.ToolCallStart -> flush()
            is AgentEvent.InteractionRequested -> {
                flush()
                when (val interaction = event.interaction) {
                    is PendingInteraction.Approval -> add(AgentProgress.ApprovalRequested(interaction.toolCallName, interaction.trustHint ?: interaction.reason ?: "Review the action's details in the app"))
                    is PendingInteraction.Questions -> add(AgentProgress.Question(interaction.questions.joinToString("; ") { it.label }.take(600)))
                    else -> {}
                }
            }
            is AgentEvent.InteractionResolved -> event.allowed?.let { add(AgentProgress.ApprovalResolved(it)) }
            else -> {}
        }
    }
}

/** An external run's progress from its snapshots; like [NativeVoiceProgress], the latest message waits to be superseded. */
internal class VoiceAgentProgress(initial: ExecutionSnapshot) {
    private var pending = initial.pending.associateBy { it.id }
    private val reported = initial.messages.map { it.id }.toMutableSet()

    fun update(snapshot: ExecutionSnapshot): List<AgentProgress> = buildList {
        val current = snapshot.pending.associateBy { it.id }
        val asking = current.keys.any { it !in pending }
        val settled = if (asking) snapshot.messages else snapshot.messages.dropLast(1)
        settled.filter { it.role == "assistant" && it.text.isNotBlank() && reported.add(it.id) }.forEach { add(AgentProgress.Text(it.text)) }
        pending.values.filter { it.id !in current && it.isApproval() }
            .forEach { add(AgentProgress.ApprovalResolved(null)) }
        current.values.filter { it.id !in pending }.forEach { request ->
            when (request) {
                is PendingInteraction.HarnessApproval -> add(AgentProgress.ApprovalRequested(request.title, request.description ?: "Review the action's details in the app"))
                is PendingInteraction.Approval -> add(AgentProgress.ApprovalRequested(request.toolCallName, request.trustHint ?: request.reason ?: "Review the action's details in the app"))
                is PendingInteraction.Questions -> add(AgentProgress.Question(request.questions.joinToString("; ") { it.label }.take(600)))
                else -> add(AgentProgress.Question(""))
            }
        }
        pending = current
    }
}

private fun PendingInteraction.isApproval() = this is PendingInteraction.Approval || this is PendingInteraction.HarnessApproval
