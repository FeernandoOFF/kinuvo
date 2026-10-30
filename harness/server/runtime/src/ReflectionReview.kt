package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.harness.core.knowledge.ChangeSource
import kinuvo.harness.core.knowledge.ReflectionRevision
import kinuvo.harness.core.knowledge.rememberReflectionReview
import kinuvo.harness.core.knowledge.KnowledgeTool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Session log holds drafts and their decisions, so review survives a Server restart. */
class ReflectionReview(private val harness: HarnessRuntime) {
    private val lock = Mutex()

    suspend fun respond(agent: Agent, sessionId: String, proposalId: String, approve: Boolean, note: String? = null) = lock.withLock {
        val entries = harness.store.entries(agent.id, sessionId)
        val reviewed = entries.filterIsInstance<SessionEntry.ReflectionReview>().firstOrNull { it.proposalId == proposalId }
        if (reviewed != null) {
            check(reviewed.approved == approve && reviewed.note == note?.trim()?.takeIf { it.isNotEmpty() }) { "This proposal was already reviewed" }
            return@withLock
        }
        val proposal = entries.flatMap { when (it) {
            is SessionEntry.Reflection -> it.result.proposals
            is SessionEntry.ReflectionReview -> it.result?.proposals.orEmpty()
            else -> emptyList()
        } }
            .firstOrNull { it.id == proposalId } ?: error("No Reflection proposal '$proposalId' in this Session")
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val revision = cleanNote?.let { ReflectionRevision(proposal, it, approve) }
        fun checkSource() {
            val scope = when (proposal.tool) {
                ToolNames.KNOWLEDGE -> harness.knowledge.scope()
                "conventions" -> harness.knowledge.provider()
                else -> return
            }
            check(proposal.scope == scope) { "The Knowledge source changed; review a fresh proposal" }
        }
        if (revision != null) checkSource()
        val result = revision?.let { harness.reflect(agent, sessionId, entries, it).also { checkSource() } }
        withContext(NonCancellable) {
            if (approve && cleanNote == null) when (proposal.tool) {
                ToolNames.KNOWLEDGE -> if (harness.knowledge.change(proposal.id) == null) {
                    KnowledgeTool(harness.knowledge, ChangeSource(agent.id, sessionId, proposal.id))
                        .executeReviewed(proposal.args, proposal.note, requireNotNull(proposal.scope))
                }
                "conventions" -> harness.knowledge.saveConventionsReviewed(requireNotNull(proposal.scope),
                    requireNotNull(proposal.conventionsBefore), proposal.args.requireString("content"))
                "skill_manage" -> harness.skills.saveReviewed(agent, proposal.args.requireString("name"),
                    proposal.args.requireString("content"), proposal.skillBefore)
                else -> error("Unsupported Reflection destination")
            }
            if (!approve || revision?.lasting == false) harness.docs.rememberReflectionReview(agent.id, proposal, cleanNote.takeIf { revision?.lasting != true }, !approve)
            harness.log.append(agent.id, sessionId, SessionEntry.ReflectionReview(proposalId, approve, cleanNote, result))
        }
    }
}
