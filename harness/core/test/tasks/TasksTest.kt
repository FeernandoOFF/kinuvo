package kinuvo.harness.core.tasks

import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.FileBoard
import kinuvo.harness.core.store.LocalTaskProvider
import kinuvo.protocol.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksTest {
    private val home = TestHome()
    private val provider = LocalTaskProvider(Path(home.files.home, "tasks.json"))
    private var config = TasksConfig(lists = mapOf("Inbox" to "default"))
    private val tasks = Tasks(listOf(provider), FileBoard(Path(home.files.home, "board.json")), { config }, { setOf("default", "travel") }, { LocalDate(2026, 10, 1) })

    @Test
    fun assigneeComesFromBoardThenMentionThenList() = runTest {
        val byList = tasks.create("Buy milk")
        assertEquals("default", byList.assignee)
        assertEquals("travel", tasks.create("Renew passport @travel").assignee)
        assertEquals("default", tasks.create("Email @someone").assignee)
        assertEquals("travel", tasks.update(byList.id, assignee = "travel").assignee)
        assertNull(tasks.update(byList.id, assignee = "").assignee)
        assertFailsWith<IllegalArgumentException> { tasks.update(byList.id, assignee = "nobody") }
        assertFailsWith<IllegalArgumentException> { tasks.create("Ghost's", assignee = "nobody") }
        assertNull(tasks.create("Mine, not @travel-desk's", assignee = "").assignee)
        assertEquals("default", tasks.create("Ask @travel-desk").assignee)
    }

    @Test
    fun stuckTasksWaitAfterARestart() = runTest {
        val t = tasks.create("Renew passport")
        tasks.claim(t.id)
        tasks.reconcile { false }
        assertEquals(TaskStatus.Waiting, tasks.get(t.id).status)
    }

    @Test
    fun readyTasksAreAssignedTodoAndWithinTheWindow() = runTest {
        val soon = tasks.create("Soon", due = LocalDate(2026, 10, 3))
        tasks.create("Later", due = LocalDate(2026, 10, 20))
        val undated = tasks.create("Whenever")
        tasks.update(tasks.create("Nobody's").id, assignee = "")
        assertEquals(setOf(soon.id, undated.id), tasks.ready().map { it.id }.toSet())
    }

    @Test
    fun blankTasksAreLeftOut() = runTest {
        val list = provider.lists().first().id
        provider.create(list, " ", "", null)
        val kept = tasks.create("Buy milk")
        assertEquals(listOf(kept.id), tasks.all().map { it.id })
        assertEquals(listOf(kept.id), tasks.ready().map { it.id })
    }

    @Test
    fun runsMoveTasksAlongAndDoneIsTheProviders() = runTest {
        val t = tasks.create("Renew passport")
        assertTrue(tasks.claim(t.id))
        assertFalse(tasks.claim(t.id))
        tasks.started(t.id, "s1")
        assertEquals(TaskStatus.InProgress, tasks.get(t.id).status)
        assertTrue(tasks.ready().none { it.id == t.id })
        tasks.finished(t.id)
        assertEquals(TaskStatus.Waiting, tasks.get(t.id).status)

        assertFalse(provider.tasks("inbox", includeDone = true).single().done)
        tasks.appendReply(t.id, "Kinuvo", "Form drafted")
        assertEquals("Kinuvo: Form drafted", tasks.get(t.id).notes)
        assertEquals(TaskStatus.Done, tasks.update(t.id, status = TaskStatus.Done).status)
        assertTrue(provider.tasks("inbox", includeDone = true).single().done)
        assertTrue(tasks.all().isEmpty())
        assertEquals(TaskStatus.Todo, tasks.update(t.id, status = TaskStatus.Todo).status)
        assertEquals(listOf("s1"), tasks.get(t.id).sessionIds)
    }

    @Test
    fun toolListsCreatesAndUpdates() = runTest {
        val tool = TasksTool(tasks, home.agent)
        tool.execute(buildJsonObject { put("action", "create"); put("title", "Book flights"); put("due", "2026-10-05"); put("assignee", "travel") })
        val id = tasks.all().single().id
        assertTrue("Book flights" in tool.execute(buildJsonObject { put("action", "list") }))
        assertEquals("No open tasks.", tool.execute(buildJsonObject { put("action", "list"); put("mine", "true") }))
        tool.execute(buildJsonObject { put("action", "update"); put("id", id); put("status", "waiting"); put("due", "none"); put("assignee", "none") })
        assertEquals(TaskStatus.Waiting, tasks.get(id).status)
        assertNull(tasks.get(id).due)
        assertNull(tasks.get(id).assignee)
        assertFailsWith<IllegalArgumentException> { tool.execute(buildJsonObject { put("action", "create"); put("title", "x"); put("due", "soon") }) }
    }

    @Test
    fun assignedRunCanFinishOnlyItsOwnTask() = runTest {
        val assigned = tasks.create("Assigned")
        val other = tasks.create("Other")
        val tool = TasksTool(tasks, home.agent, assigned.id)
        assertFalse("Other" in tool.execute(buildJsonObject { put("action", "list") }))
        assertFailsWith<IllegalArgumentException> {
            tool.execute(buildJsonObject { put("action", "update"); put("id", other.id); put("status", "done") })
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(buildJsonObject { put("action", "create"); put("title", "New") })
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(buildJsonObject { put("action", "comment"); put("id", assigned.id); put("text", "Progress") })
        }
        assertEquals("", tasks.get(assigned.id).notes)
        tool.execute(buildJsonObject { put("action", "update"); put("id", assigned.id); put("status", "done") })
        assertEquals(TaskStatus.Done, tasks.get(assigned.id).status)
        assertEquals(TaskStatus.Todo, tasks.get(other.id).status)
    }

}
