package kinuvo.harness.core.knowledge

import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.AgentDocs
import kinuvo.protocol.ReflectionProposal
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class ReflectionFeedback(val proposalId: String, val stage: String, val gist: String, val note: String?, val declined: Boolean)

private val feedbackSerializer = ListSerializer(ReflectionFeedback.serializer())

private fun feedback(text: String): List<ReflectionFeedback> =
    if (text.isBlank()) emptyList() else Json.decodeFromString(feedbackSerializer, text)

suspend fun AgentDocs.rememberReflectionReview(agentId: String, proposal: ReflectionProposal, note: String?, declined: Boolean) {
    val stage = if (proposal.tool == "skill_manage") "Skills" else "Knowledge"
    val entry = ReflectionFeedback(proposal.id, stage, proposal.args.toString().take(400), note?.take(600), declined)
    update(agentId, AgentDoc.ReflectionFeedback) { old ->
        Json.encodeToString(feedbackSerializer, (feedback(old).filterNot { it.proposalId == proposal.id } + entry).takeLast(20))
    }
}

internal suspend fun AgentDocs.reflectionFeedback(agentId: String, stage: ReflectionStage): String {
    val recent = feedback(read(agentId, AgentDoc.ReflectionFeedback)).filter { it.stage == stage.name }
    return if (recent.isEmpty()) "" else "Recent ${stage.name} review feedback:\n" + recent.joinToString("\n") {
        "- ${if (it.declined) "Declined" else "Reviewed"}: ${it.gist.take(160)}" + (it.note?.let { note -> " — ${note.take(240)}" } ?: "")
    } + "\nUse these decisions to choose useful proposals and address the user's corrections.\n\n"
}
