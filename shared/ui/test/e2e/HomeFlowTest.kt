package kinuvo.ui.e2e

import kotlin.test.Test

class HomeFlowTest {
    @Test fun boardCreatesATaskAndShowsLiveUpdates() = appTest("board", "mobile-features") {
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
        push("""{"type":"event","event":"task.changed","payload":{"id":"created","title":"Task updated by server","listId":"personal","listName":"Personal","status":"Todo"},"seq":20}""")
        await("Task updated by server")
        gone("New mobile task")
        tap("Show done")
        await("Hide done")
        repeat(6) { if (!visible("Completed fixture task")) swipe(60, 60, 10, 60) }
        await("Completed fixture task")
        screenshot("board-done")
        requestsAtLeast("mobile/task-lists")
        requestsAtLeast("mobile/tasks")
        requests("mobile/task-create", 1)
    }

    @Test fun knowledgeSearchesAndFollowsLinks() = appTest("knowledge", "mobile-features") {
        pair()
        openHome()
        tap("Knowledge")
        screenshot("knowledge")
        tap("Search people, projects, places…")
        type("Missing")
        requestsAtLeast("mobile/knowledge-empty")
        await("No matches.")
        clear()
        type("Kinuvo")
        requestsAtLeast("mobile/knowledge-search")
        await("Kinuvo project")
        screenshot("knowledge-search")
        tap("Kinuvo project")
        await("Ref project")
        await("Linked notes")
        screenshot("knowledge-note")
        tap("Fernando")
        await("Maintains Kinuvo and Mockstro.")
        await("Ref person")
        tap("Back")
        await("Kinuvo project")
        requestsAtLeast("mobile/knowledge-changes")
        requestsAtLeast("mobile/knowledge-empty")
        requestsAtLeast("mobile/knowledge-search")
        requests("mobile/knowledge-project", 1)
        requests("mobile/knowledge-person", 1)
    }
}
