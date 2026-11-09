package kinuvo.harness.server

import kinuvo.harness.core.voice.AgentProgress
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ExecutionPrompt
import kinuvo.protocol.ExecutionRequestKind
import kinuvo.protocol.ExecutionSnapshot

/** A native run's progress; a message is reported only once more work follows it, since the last one is the reply. */
internal class NativeVoiceProgress {
    private var text: String? = null

    fun update(event: AgentEvent): List<AgentProgress> = buildList {
        fun flush() { text?.let { add(AgentProgress.Text(it)) }; text = null }
        when (event) {
            is AgentEvent.TextMessageEnd -> { flush(); text = event.text.takeIf { it.isNotBlank() } }
            is AgentEvent.ToolCallStart -> flush()
            is AgentEvent.ToolCallResult -> { flush(); if (event.content.isNotBlank()) add(AgentProgress.Text(event.content)) }
            is AgentEvent.ApprovalRequested -> { flush(); add(AgentProgress.ApprovalRequested(event.toolCallName, event.trustHint ?: event.reason ?: "Review the action's details in the app")) }
            is AgentEvent.ApprovalResolved -> add(AgentProgress.ApprovalResolved(event.allowed))
            is AgentEvent.QuestionsAsked -> { flush(); add(AgentProgress.Question(event.questions.joinToString("; ") { it.label }.take(600))) }
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
        pending.values.filter { it.id !in current && it.kind == ExecutionRequestKind.Approval }
            .forEach { add(AgentProgress.ApprovalResolved(null)) }
        current.values.filter { it.id !in pending }.forEach { request ->
            val prompt = request.prompt
            if (request.kind == ExecutionRequestKind.Approval) {
                val approval = prompt as? ExecutionPrompt.Approval
                add(AgentProgress.ApprovalRequested(approval?.title ?: "Agent action", approval?.description ?: "Review the action's details in the app"))
            } else add(AgentProgress.Question((prompt as? ExecutionPrompt.Questions)?.asked?.questions.orEmpty().joinToString("; ") { it.label }.take(600)))
        }
        pending = current
    }
}
