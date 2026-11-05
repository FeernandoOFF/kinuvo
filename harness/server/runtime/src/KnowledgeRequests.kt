package kinuvo.harness.server

import kinuvo.protocol.*

/** Knowledge notes, changes and settings. */
internal class KnowledgeRequests(private val harness: HarnessRuntime) {
    suspend fun handle(r: Frame.Request): Frame.Response? = when (r.method) {
        Api.KnowledgeSearch.name -> respond(r, Api.KnowledgeSearch) { harness.knowledge.search(it.query, it.type, it.limit.coerceIn(1, MAX_PAGE)) }
        Api.KnowledgeNote.name -> respond(r, Api.KnowledgeNote) { harness.knowledge.find(it.ref) ?: error("No note '${it.ref}'") }
        Api.KnowledgeChanges.name -> respond(r, Api.KnowledgeChanges) { harness.knowledge.changes(it.limit.coerceIn(1, MAX_PAGE)) }
        Api.UndoKnowledgeChange.name -> respond(r, Api.UndoKnowledgeChange) { harness.knowledge.undo(it.changeId) }
        Api.KnowledgeSettings.name -> respond(r, Api.KnowledgeSettings) { harness.knowledgeSetup() }
        Api.SaveKnowledgeSettings.name -> respond(r, Api.SaveKnowledgeSettings) { harness.saveKnowledgeSettings(it) }
        Api.TestKnowledge.name -> respond(r, Api.TestKnowledge) { harness.testKnowledge(it) }
        else -> null
    }
}
