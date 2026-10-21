package kinuvo.harness.core.knowledge

import kinuvo.protocol.ReflectionProposal
import kotlinx.serialization.json.Json

class ReflectionRevision(val proposal: ReflectionProposal, val note: String) {
    internal val stage get() = if (proposal.tool == "skill_manage") ReflectionStage.Skills else ReflectionStage.Knowledge

    internal fun prompt(): String = """The user reviewed one Reflection proposal. Revise just this proposal using their note and the stage's context and tools. Stage the revised content for Approval again; the original remains unapplied. Cite review-note for the user's correction and proposal for its original evidence.

Proposal:
${Json.encodeToString(ReflectionProposal.serializer(), proposal)}

[review-note] User: $note

"""
}
