package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.harness.core.knowledge.ChangeSource
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

    suspend fun respond(agent: Agent, sessionId: String, proposalId: String, approve: Boolean) = lock.withLock {
        val entries = harness.store.entries(agent, sessionId)
        val reviewed = entries.filterIsInstance<SessionEntry.ReflectionReview>().firstOrNull { it.proposalId == proposalId }
        if (reviewed != null) {
            check(reviewed.approved == approve) { "This proposal was already reviewed" }
            return@withLock
        }
        val proposal = entries.filterIsInstance<SessionEntry.Reflection>().flatMap { it.result.proposals }
            .firstOrNull { it.id == proposalId } ?: error("No Reflection proposal '$proposalId' in this Session")
        withContext(NonCancellable) {
            if (approve) when (proposal.tool) {
                ToolNames.KNOWLEDGE -> if (harness.knowledge.change(proposal.id) == null) {
                    KnowledgeTool(harness.knowledge, ChangeSource(agent.id, sessionId, proposal.id))
                        .executeReviewed(proposal.args, proposal.note, requireNotNull(proposal.scope))
                }
                "skill_manage" -> harness.skills.saveReviewed(agent, proposal.args.requireString("name"),
                    proposal.args.requireString("content"), proposal.skillBefore)
                else -> error("Unsupported Reflection destination")
            }
            harness.log.append(agent, sessionId, SessionEntry.ReflectionReview(proposalId, approve))
        }
    }
}
