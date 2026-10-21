package kinuvo.harness.core.knowledge

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.requireString
import kinuvo.protocol.ReflectionProposal
import kinuvo.protocol.ReflectionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class ReflectionRevision(val proposal: ReflectionProposal, val note: String, val approve: Boolean) {
    internal val stage get() = if (proposal.tool == "skill_manage") ReflectionStage.Skills else ReflectionStage.Knowledge
    var lasting: Boolean? = null
        private set

    internal fun prompt(): String = """The user reviewed one Reflection proposal. Use their note and this stage's context and tools. Call reflection_feedback once to decide whether the note states a lasting rule or concerns just this proposal. Cite review-note for the user's correction and proposal for its original evidence.

${if (approve) "Revise just this proposal and stage its corrected content for Approval again; the original remains unapplied." else "The user rejected this proposal. Keep its original change unapplied; propose only lasting learning from the note."}

For a lasting Knowledge rule, propose a Conventions edit in the user's words about how they organize Knowledge. Preserve the rest of their Conventions. For a lasting Skill rule, improve the reviewed Skill through skill_manage. Each edit waits for Approval. A one-off correction goes into recent Reflection feedback.

Proposal:
${Json.encodeToString(ReflectionProposal.serializer(), proposal)}

[review-note] User: $note

"""

    internal fun decisionTool() = object : Tool {
        override val descriptor = ToolDescriptor("reflection_feedback", "Classify this review note before finishing.", requiredParameters = listOf(
            ToolParameterDescriptor("lasting", "Whether the note states a rule useful beyond this proposal", ToolParameterType.Boolean),
        ))
        override suspend fun execute(args: JsonObject): String {
            val decision = requireNotNull(args["lasting"]).jsonPrimitive.boolean
            check(lasting == null || lasting == decision) { "This review note was already classified" }
            lasting = decision
            return if (decision) "Stage the lasting learning for Approval." else "The correction will be kept in recent Reflection feedback."
        }
    }

    internal fun validate(result: ReflectionResult) {
        check(result.error == null) { result.error.orEmpty() }
        check(lasting != null) { "Reflection did not classify the review note" }
        if (approve) check(result.proposals.any { it.tool == proposal.tool }) { "Reflection did not produce a revised proposal" }
        if (lasting == true) check(result.proposals.any {
            if (stage == ReflectionStage.Knowledge) it.tool == "conventions"
            else it.tool == "skill_manage" && it.args.requireString("name") == proposal.args.requireString("name")
        }) { "Reflection did not propose lasting learning" }
        if (!approve && lasting == false) check(result.proposals.isEmpty()) { "A one-off rejection needs only recent feedback" }
        check(lasting == true || proposal.tool == "conventions" || result.proposals.none { it.tool == "conventions" }) { "Conventions need a lasting rule" }
    }
}

internal fun conventionsTool() = object : Tool {
    override val descriptor = ToolDescriptor("conventions", "Propose how the user organizes Knowledge, preserving their other Conventions.", requiredParameters = listOf(
        ToolParameterDescriptor("action", "edit", ToolParameterType.String),
        ToolParameterDescriptor("content", "The complete revised Conventions", ToolParameterType.String),
    ))
    override suspend fun execute(args: JsonObject): String = error("Conventions edits need Approval")
}
