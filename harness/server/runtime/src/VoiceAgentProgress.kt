package kinuvo.harness.server

import kinuvo.harness.core.voice.AgentProgress
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ExecutionPrompt
import kinuvo.protocol.ExecutionRequestKind
import kinuvo.protocol.ExecutionSnapshot

internal fun AgentEvent.voiceProgress(): AgentProgress? = when (this) {
    is AgentEvent.ApprovalRequested -> AgentProgress.ApprovalRequested(toolCallName, trustHint ?: reason ?: "Review the action's details in the app")
    is AgentEvent.ApprovalResolved -> AgentProgress.ApprovalResolved(allowed)
    is AgentEvent.QuestionsAsked -> AgentProgress.Question(questions.joinToString("; ") { it.label }.take(600))
    is AgentEvent.TextMessageEnd -> AgentProgress.Text(text)
    else -> null
}

internal class VoiceAgentProgress(initial: ExecutionSnapshot) {
    private var pending = initial.pending.associateBy { it.id }
    private val messages = initial.messages.associate { it.id to it.text }.toMutableMap()

    fun update(snapshot: ExecutionSnapshot): List<AgentProgress> = buildList {
        val current = snapshot.pending.associateBy { it.id }
        pending.values.filter { it.id !in current && it.kind == ExecutionRequestKind.Approval }
            .forEach { add(AgentProgress.ApprovalResolved(null)) }
        current.values.filter { it.id !in pending }.forEach { request ->
            val prompt = request.prompt
            if (request.kind == ExecutionRequestKind.Approval) {
                val approval = prompt as? ExecutionPrompt.Approval
                add(AgentProgress.ApprovalRequested(approval?.title ?: "Agent action", approval?.description ?: "Review the action's details in the app"))
            } else add(AgentProgress.Question((prompt as? ExecutionPrompt.Questions)?.asked?.questions.orEmpty().joinToString("; ") { it.label }.take(600)))
        }
        snapshot.messages.filter { it.role == "assistant" && it.text.isNotBlank() && messages[it.id] != it.text }.forEach {
            add(AgentProgress.Text(it.text))
        }
        snapshot.messages.forEach { messages[it.id] = it.text }
        pending = current
    }
}
