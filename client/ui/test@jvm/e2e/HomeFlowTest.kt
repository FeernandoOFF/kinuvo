package kinuvo.ui.e2e

import kinuvo.protocol.Api
import kinuvo.protocol.board.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeFlowTest {
    @Test fun boardCreatesATaskAndShowsLiveUpdates() = appTest("board", scenario = {
        task("Plan mobile tests")
        task("Completed fixture task", status = TaskStatus.Done)
    }) {
        pair()
        openHome()
        tap("Board")
        await("Plan mobile tests")
        screenshot("board")
        tap("New task")
        tap("Renew passport")
        type("New mobile task")
        tap("Add task")
        await("New mobile task")
        val created = server.harness.tasks.all().single { it.title == "New mobile task" }
        server.harness.tasks.update(created.id, title = "Task updated by server")
        await("Task updated by server")
        gone("New mobile task")
        repeat(6) { if (!visible("Completed fixture task")) swipe(60, 60, 10, 60) }
        await("Completed fixture task")
        screenshot("board-done")
        requestsAtLeast(Api.TaskLists)
        requestsAtLeast(Api.Tasks)
        requests(Api.CreateTask, 1)
        assertEquals(listOf("New mobile task"), server.spy.params(Api.CreateTask).map { it.title })
    }

    @Test fun knowledgeSearchesAndFollowsLinks() = appTest("knowledge", scenario = {
        note("human", "Fernando", "Maintains Kinuvo and Mockstro.")
        note("project", "Kinuvo project", "Testing mobile workflows with [[Fernando]].")
    }) {
        pair()
        openHome()
        tap("Knowledge")
        screenshot("knowledge")
        tap("Search people, projects, places…")
        type("Missing")
        requestsAtLeast(Api.KnowledgeSearch)
        await("No matches.")
        clear()
        type("Kinuvo")
        requestsAtLeast(Api.KnowledgeSearch, 2)
        await("Kinuvo project")
        screenshot("knowledge-search")
        tap("Kinuvo project")
        await("Linked notes")
        screenshot("knowledge-note")
        tap("Fernando")
        await("Maintains Kinuvo and Mockstro.")
        tap("Back")
        await("Kinuvo project")
        requestsAtLeast(Api.KnowledgeChanges)
        requestsAtLeast(Api.KnowledgeSearch, 2)
        requests(Api.KnowledgeNote, 2)
        assertEquals(setOf("Missing", "Kinuvo"), server.spy.params(Api.KnowledgeSearch).map { it.query }.toSet())
    }
}
