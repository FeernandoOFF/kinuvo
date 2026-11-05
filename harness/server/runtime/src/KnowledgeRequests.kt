package kinuvo.harness.server

import kinuvo.protocol.*

/** Knowledge notes, changes and settings. */
internal class KnowledgeRequests(private val harness: HarnessRuntime) {
    fun <C> register(handlers: RequestHandlers<C>) = with(handlers) {
        on(Api.KnowledgeSearch) { harness.knowledge.search(it.query, it.type, it.limit.coerceIn(1, MAX_PAGE)) }
        on(Api.KnowledgeNote) { harness.knowledge.find(it.ref) ?: error("No note '${it.ref}'") }
        on(Api.KnowledgeChanges) { harness.knowledge.changes(it.limit.coerceIn(1, MAX_PAGE)) }
        on(Api.UndoKnowledgeChange) { harness.knowledge.undo(it.changeId) }
        on(Api.KnowledgeSettings) { harness.knowledgeSetup() }
        on(Api.SaveKnowledgeSettings) { harness.saveKnowledgeSettings(it) }
        on(Api.TestKnowledge) { harness.testKnowledge(it) }
    }
}
