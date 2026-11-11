package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.server.MAX_PAGE
import kinuvo.server.ServerError

internal fun RouteBuilder.knowledgeRoutes() {
    device(Api.KnowledgeSearch) { server.harness.knowledge.search(it.query, it.type, it.limit.coerceIn(1, MAX_PAGE)) }
    device(Api.KnowledgeNote) { server.harness.knowledge.find(it.ref) ?: throw ServerError.NotFound("No note '${it.ref}'") }
    device(Api.KnowledgeChanges) { server.harness.knowledge.changes(it.limit.coerceIn(1, MAX_PAGE)) }
    handle(Api.UndoKnowledgeChange) { server.harness.knowledge.undo(it.changeId) }
    handle(Api.KnowledgeSettings) { server.harness.knowledgeSetup() }
    handle(Api.SaveKnowledgeSettings) { server.harness.saveKnowledgeSettings(it) }
    handle(Api.TestKnowledge) { server.harness.testKnowledge(it) }
}
